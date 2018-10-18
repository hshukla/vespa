// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance;

import com.yahoo.config.provision.NodeType;
import com.yahoo.io.IOUtils;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.hosted.dockerapi.Container;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder;
import com.yahoo.vespa.hosted.node.admin.task.util.file.UnixPath;
import com.yahoo.vespa.hosted.node.admin.util.SecretAgentCheckConfig;
import com.yahoo.vespa.hosted.node.admin.maintenance.coredump.CoredumpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameMatches;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.olderThan;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.IOExceptionUtil.ifExists;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.IOExceptionUtil.uncheck;

/**
 * @author freva
 */
public class StorageMaintainer {
    private static final Logger logger = Logger.getLogger(StorageMaintainer.class.getName());
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final DockerOperations dockerOperations;
    private final CoredumpHandler coredumpHandler;
    private final Path archiveContainerStoragePath;

    public StorageMaintainer(DockerOperations dockerOperations, CoredumpHandler coredumpHandler, Path archiveContainerStoragePath) {
        this.dockerOperations = dockerOperations;
        this.coredumpHandler = coredumpHandler;
        this.archiveContainerStoragePath = archiveContainerStoragePath;
    }

    public void writeMetricsConfig(NodeAgentContext context, NodeSpec node) {
        List<SecretAgentCheckConfig> configs = new ArrayList<>();

        // host-life
        Path hostLifeCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_host_life");
        SecretAgentCheckConfig hostLifeSchedule = new SecretAgentCheckConfig("host-life", 60, hostLifeCheckPath);
        configs.add(annotatedCheck(context, node, hostLifeSchedule));

        // ntp
        Path ntpCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_ntp");
        SecretAgentCheckConfig ntpSchedule = new SecretAgentCheckConfig("ntp", 60, ntpCheckPath);
        configs.add(annotatedCheck(context, node, ntpSchedule));

        // coredumps (except for the done coredumps which is handled by the host)
        Path coredumpCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_coredumps");
        SecretAgentCheckConfig coredumpSchedule = new SecretAgentCheckConfig("system-coredumps-processing", 300,
                coredumpCheckPath, "--application", "system-coredumps-processing", "--lastmin",
                "129600", "--crit", "1", "--coredir", context.pathInNodeUnderVespaHome("var/crash/processing").toString());
        configs.add(annotatedCheck(context, node, coredumpSchedule));

        // athenz certificate check
        Path athenzCertExpiryCheckPath = context.pathInNodeUnderVespaHome("libexec64/yms/yms_check_athenz_certs");
        SecretAgentCheckConfig athenzCertExpirySchedule = new SecretAgentCheckConfig("athenz-certificate-expiry", 60,
                 athenzCertExpiryCheckPath, "--threshold", "20")
                .withRunAsUser("root");
        configs.add(annotatedCheck(context, node, athenzCertExpirySchedule));

        if (context.nodeType() != NodeType.config) {
            // vespa-health
            Path vespaHealthCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_vespa_health");
            SecretAgentCheckConfig vespaHealthSchedule = new SecretAgentCheckConfig("vespa-health", 60, vespaHealthCheckPath, "all");
            configs.add(annotatedCheck(context, node, vespaHealthSchedule));

            // vespa
            Path vespaCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_vespa");
            SecretAgentCheckConfig vespaSchedule = new SecretAgentCheckConfig("vespa", 60, vespaCheckPath, "all");
            configs.add(annotatedCheck(context, node, vespaSchedule));
        }

        if (context.nodeType() == NodeType.config) {
            // configserver
            Path configServerCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_ymonsb2");
            SecretAgentCheckConfig configServerSchedule = new SecretAgentCheckConfig("configserver", 60,
                    configServerCheckPath, "-zero", "configserver");
            configs.add(annotatedCheck(context, node, configServerSchedule));

            //zkbackupage
            Path zkbackupCheckPath = context.pathInNodeUnderVespaHome("libexec/yamas2/yms_check_file_age.py");
            SecretAgentCheckConfig zkbackupSchedule = new SecretAgentCheckConfig("zkbackupage", 300,
                    zkbackupCheckPath, "-f", context.pathInNodeUnderVespaHome("var/vespa-hosted/zkbackup.stat").toString(),
                    "-m", "150", "-a", "config-zkbackupage");
            configs.add(annotatedCheck(context, node, zkbackupSchedule));
        }

        if (context.nodeType() == NodeType.proxy) {
            //routing-configage
            Path routingAgeCheckPath = context.pathInNodeUnderVespaHome("libexec/yamas2/yms_check_file_age.py");
            SecretAgentCheckConfig routingAgeSchedule = new SecretAgentCheckConfig("routing-configage", 60,
                    routingAgeCheckPath, "-f", context.pathInNodeUnderVespaHome("var/vespa-hosted/routing/nginx.conf").toString(),
                    "-m", "90", "-a", "routing-configage");
            configs.add(annotatedCheck(context, node, routingAgeSchedule));

            //ssl-check
            Path sslCheckPath = context.pathInNodeUnderVespaHome("libexec/yms/yms_check_ssl_status");
            SecretAgentCheckConfig sslSchedule = new SecretAgentCheckConfig("ssl-status", 300,
                    sslCheckPath, "-e", "localhost", "-p", "4443", "-t", "30");
            configs.add(annotatedCheck(context, node, sslSchedule));
        }

        // Write config and restart yamas-agent
        Path yamasAgentFolder = context.pathOnHostFromPathInNode("/etc/yamas-agent");

        // TODO: Remove after 6.301
        ifExists(() -> Files.setPosixFilePermissions(yamasAgentFolder, PosixFilePermissions.fromString("rw-r--r--")));

        configs.forEach(s -> uncheck(() -> s.writeTo(yamasAgentFolder)));
        dockerOperations.executeCommandInContainerAsRoot(context, "service", "yamas-agent", "restart");
    }

    private SecretAgentCheckConfig annotatedCheck(NodeAgentContext context, NodeSpec node, SecretAgentCheckConfig check) {
        check.withTag("namespace", "Vespa")
                .withTag("role", SecretAgentCheckConfig.nodeTypeToRole(node.getNodeType()))
                .withTag("flavor", node.getFlavor())
                .withTag("canonicalFlavor", node.getCanonicalFlavor())
                .withTag("state", node.getState().toString())
                .withTag("zone", String.format("%s.%s", context.zoneId().environment().value(), context.zoneId().regionName().value()));
        node.getParentHostname().ifPresent(parent -> check.withTag("parentHostname", parent));
        node.getOwner().ifPresent(owner -> check
                .withTag("tenantName", owner.getTenant())
                .withTag("app", owner.getApplication() + "." + owner.getInstance())
                .withTag("applicationName", owner.getApplication())
                .withTag("instanceName", owner.getInstance())
                .withTag("applicationId", owner.getTenant() + "." + owner.getApplication() + "." + owner.getInstance()));
        node.getMembership().ifPresent(membership -> check
                .withTag("clustertype", membership.getClusterType())
                .withTag("clusterid", membership.getClusterId()));
        node.getVespaVersion().ifPresent(version -> check.withTag("vespaVersion", version));

        return check;
    }

    public Optional<Long> getDiskUsageFor(NodeAgentContext context) {
        Path containerDir = context.pathOnHostFromPathInNode("/");
        try {
            return Optional.of(getDiskUsedInBytes(containerDir));
        } catch (Throwable e) {
            context.log(logger, LogLevel.WARNING, "Problems during disk usage calculations in " + containerDir.toAbsolutePath(), e);
            return Optional.empty();
        }
    }

    // Public for testing
    long getDiskUsedInBytes(Path path) throws IOException, InterruptedException {
        if (!Files.exists(path)) return 0;

        Process duCommand = new ProcessBuilder().command("du", "-xsk", path.toString()).start();
        if (!duCommand.waitFor(60, TimeUnit.SECONDS)) {
            duCommand.destroy();
            duCommand.waitFor();
            throw new RuntimeException("Disk usage command timed out, aborting.");
        }
        String output = IOUtils.readAll(new InputStreamReader(duCommand.getInputStream()));
        String[] results = output.split("\t");
        if (results.length != 2) {
            throw new RuntimeException("Result from disk usage command not as expected: " + output);
        }

        long diskUsageKB = Long.valueOf(results[0]);
        return diskUsageKB * 1024;
    }


    /** Deletes old log files for vespa, nginx, logstash, etc. */
    public void removeOldFilesFromNode(NodeAgentContext context) {
        Path[] logPaths = {
                context.pathInNodeUnderVespaHome("logs/elasticsearch2"),
                context.pathInNodeUnderVespaHome("logs/logstash2"),
                context.pathInNodeUnderVespaHome("logs/daemontools_y"),
                context.pathInNodeUnderVespaHome("logs/nginx"),
                context.pathInNodeUnderVespaHome("logs/vespa")
        };

        for (Path pathToClean : logPaths) {
            Path path = context.pathOnHostFromPathInNode(pathToClean);
            FileFinder.files(path)
                    .match(olderThan(Duration.ofDays(3)).and(nameMatches(Pattern.compile(".*\\.log.+"))))
                    .maxDepth(1)
                    .deleteRecursively();
        }

        FileFinder.files(context.pathOnHostFromPathInNode(context.pathInNodeUnderVespaHome("logs/vespa/qrs")))
                .match(olderThan(Duration.ofDays(3)))
                .deleteRecursively();

        FileFinder.files(context.pathOnHostFromPathInNode(context.pathInNodeUnderVespaHome("logs/vespa/logarchive")))
                .match(olderThan(Duration.ofDays(31)))
                .deleteRecursively();

        FileFinder.directories(context.pathOnHostFromPathInNode(context.pathInNodeUnderVespaHome("var/db/vespa/filedistribution")))
                .match(olderThan(Duration.ofDays(31)))
                .deleteRecursively();
    }

    /** Checks if container has any new coredumps, reports and archives them if so */
    public void handleCoreDumpsForContainer(NodeAgentContext context, NodeSpec node, Optional<Container> container) {
        final Map<String, Object> nodeAttributes = getCoredumpNodeAttributes(context, node, container);
        coredumpHandler.converge(context, nodeAttributes);
    }

    private Map<String, Object> getCoredumpNodeAttributes(NodeAgentContext context, NodeSpec node, Optional<Container> container) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("hostname", node.getHostname());
        attributes.put("region", context.zoneId().regionName());
        attributes.put("environment", context.zoneId().environment());
        attributes.put("flavor", node.getFlavor());
        attributes.put("kernel_version", System.getProperty("os.version"));

        container.map(c -> c.image).ifPresent(image -> attributes.put("docker_image", image.asString()));
        node.getParentHostname().ifPresent(parent -> attributes.put("parent_hostname", parent));
        node.getVespaVersion().ifPresent(version -> attributes.put("vespa_version", version));
        node.getOwner().ifPresent(owner -> {
            attributes.put("tenant", owner.getTenant());
            attributes.put("application", owner.getApplication());
            attributes.put("instance", owner.getInstance());
        });
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Prepares the container-storage for the next container by deleting/archiving all the data of the current container.
     * Removes old files, reports coredumps and archives container data, runs when container enters state "dirty"
     */
    public void archiveNodeStorage(NodeAgentContext context) {
        Path logsDirInContainer = context.pathInNodeUnderVespaHome("logs");
        Path containerLogsOnHost = context.pathOnHostFromPathInNode(logsDirInContainer);
        Path containerLogsInArchiveDir = archiveContainerStoragePath
                .resolve(context.containerName().asString() + "_" + DATE_TIME_FORMATTER.format(Instant.now()) + logsDirInContainer);

        new UnixPath(containerLogsInArchiveDir).createParents();
        new UnixPath(containerLogsOnHost).moveIfExists(containerLogsInArchiveDir);
        new UnixPath(context.pathOnHostFromPathInNode("/")).deleteRecursively();
    }
}
