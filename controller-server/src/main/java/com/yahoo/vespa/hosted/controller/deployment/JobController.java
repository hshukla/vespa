package com.yahoo.vespa.hosted.controller.deployment;

import com.google.common.collect.ImmutableMap;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.LogStore;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.application.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.application.SourceRevision;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;

/**
 * A singleton owned by the controller, which contains the state and methods for controlling deployment jobs.
 *
 * Keys are the {@link ApplicationId} of the real application, for which the deployment job is run, and the
 * {@link JobType} of the real deployment to test.
 *
 * Although the deployment jobs are themselves applications, their IDs are not to be referenced.
 *
 * @author jonmv
 */
public class JobController {

    private final Controller controller;
    private final CuratorDb curator;
    private final LogStore logs;

    public JobController(Controller controller, LogStore logStore) {
        this.controller = controller;
        this.curator = controller.curator();
        this.logs = logStore;
    }

    public LogStore logs() {
        return logs;
    }

    // TODO jvenstad: Remove this, and let the DeploymentTrigger trigger directly with the correct BuildService.
    /** Returns whether the given application has registered with this build service. */
    public boolean builds(ApplicationId id) {
        return controller.applications().get(id)
                         .map(application -> application.deploymentJobs().builtInternally())
                         .orElse(false);
    }

    /** Returns a list of all application which have registered. */
    public List<ApplicationId> applications() {
        return copyOf(controller.applications().asList().stream()
                                .filter(application -> application.deploymentJobs().builtInternally())
                                .map(Application::id)
                                .iterator());
    }

    /** Returns all job types which have been run for the given application. */
    public List<JobType> jobs(ApplicationId id) {
        return copyOf(Stream.of(JobType.values())
                            .filter(type -> last(id, type).isPresent())
                            .iterator());
    }

    /** Returns an immutable map of all known runs for the given application and job type. */
    public Map<RunId, RunStatus> runs(ApplicationId id, JobType type) {
        Map<RunId, RunStatus> runs = curator.readHistoricRuns(id, type);
        last(id, type).ifPresent(run -> runs.putIfAbsent(run.id(), run));
        return ImmutableMap.copyOf(runs);
    }

    /** Returns the last run of the given type, for the given application, if one has been run. */
    public Optional<RunStatus> last(ApplicationId id, JobType type) {
        return curator.readLastRun(id, type);
    }

    /** Returns the run with the given id, provided it is still active. */
    public Optional<RunStatus> active(RunId id) {
        return last(id.application(), id.type())
                .filter(run -> ! run.hasEnded())
                .filter(run -> run.id().equals(id));
    }

    /** Returns a list of all active runs. */
    public List<RunStatus> active() {
        return copyOf(applications().stream()
                                    .flatMap(id -> Stream.of(JobType.values())
                                                         .map(type -> last(id, type))
                                                         .filter(Optional::isPresent).map(Optional::get)
                                                         .filter(run -> ! run.hasEnded()))
                                    .iterator());
    }

    /** Changes the status of the given step, for the given run, provided it is still active. */
    public void update(RunId id, Step.Status status, LockedStep step) {
        locked(id, run -> run.with(status, step));
    }

    /** Changes the status of the given run to inactive, and stores it as a historic run. */
    public void finish(RunId id) {
        locked(id, run -> { // Store the modified run after it has been written to the collection, in case the latter fails.
            RunStatus endedRun = run.finish(controller.clock().instant());
            locked(id.application(), id.type(), runs -> runs.put(run.id(), endedRun));
            return endedRun;
        });
    }

    /** Registers the given application, such that it may have deployment jobs run here. */
    void register(ApplicationId id) {
        controller.applications().lockIfPresent(id, application ->
                controller.applications().store(application.withBuiltInternally(true)));
    }

    /** Accepts and stores a new application package and test jar pair under a generated application version key. */
    public ApplicationVersion submit(ApplicationId id, SourceRevision revision,
                                     byte[] applicationPackage, byte[] applicationTestJar) {
        AtomicReference<ApplicationVersion> version = new AtomicReference<>();

        controller.applications().lockOrThrow(id, application -> {
            if ( ! application.get().deploymentJobs().builtInternally())
                throw new IllegalArgumentException(id + " is not built here!");

            long run = nextBuild(id);
            version.set(ApplicationVersion.from(revision, run));

            // TODO smorgrav: Store the pair.

            notifyOfNewSubmission(id, revision, run);
        });
        return version.get();
    }

    /** Orders a run of the given type, and returns the id of the created job. */
    public void run(ApplicationId id, JobType type) {
        controller.applications().lockIfPresent(id, application -> {
            if ( ! application.get().deploymentJobs().builtInternally())
                throw new IllegalArgumentException(id + " is not built here!");

            locked(id, type, __ -> {
                Optional<RunStatus> last = last(id, type);
                if (last.flatMap(run -> active(run.id())).isPresent())
                    throw new IllegalStateException("Can not start " + type + " for " + id + "; it is already running!");

                RunId newId = new RunId(id, type, last.map(run -> run.id().number()).orElse(0L) + 1);
                curator.writeLastRun(RunStatus.initial(newId, controller.clock().instant()));
            });
        });
    }

    /** Unregisters the given application and deletes all associated data. */
    public void unregister(ApplicationId id) {
        controller.applications().lockIfPresent(id, application -> {
            controller.applications().store(application.withBuiltInternally(false));
            for (JobType type : jobs(id))
                try (Lock __ = curator.lock(id, type)) {
                    curator.deleteJobData(id, type);
                }
        });
    }

    // TODO jvenstad: Find a more appropriate way of doing this, at least when this is the only build service.
    private long nextBuild(ApplicationId id) {
        return 1 + controller.applications().require(id).deploymentJobs()
                             .statusOf(JobType.component)
                             .flatMap(JobStatus::lastCompleted)
                             .map(JobStatus.JobRun::id)
                             .orElse(0L);
    }

    // TODO jvenstad: Find a more appropriate way of doing this when this is the only build service.
    private void notifyOfNewSubmission(ApplicationId id, SourceRevision revision, long number) {
        DeploymentJobs.JobReport report = new DeploymentJobs.JobReport(id,
                                                                       JobType.component,
                                                                       0,
                                                                       number,
                                                                       Optional.of(revision),
                                                                       Optional.empty());
        controller.applications().deploymentTrigger().notifyOfCompletion(report);
    }

    /** Locks and modifies the list of historic runs for the given application and job type. */
    private void locked(ApplicationId id, JobType type, Consumer<Map<RunId, RunStatus>> modifications) {
        try (Lock __ = curator.lock(id, type)) {
            Map<RunId, RunStatus> runs = curator.readHistoricRuns(id, type);
            modifications.accept(runs);
            curator.writeHistoricRuns(id, type, runs.values());
        }
    }

    /** Locks and modifies the run with the given id, provided it is still active. */
    private void locked(RunId id, UnaryOperator<RunStatus> modifications) {
        try (Lock __ = curator.lock(id.application(), id.type())) {
            RunStatus run = active(id).orElseThrow(() -> new IllegalArgumentException(id + " is not an active run!"));
            run = modifications.apply(run);
            curator.writeLastRun(run);
        }
    }

    /** Locks the given step, and checks none of its prerequisites are running, then performs the given actions. */
    public void locked(RunId id, Step step, Consumer<LockedStep> action) throws TimeoutException {
        try (Lock lock = curator.lock(id.application(), id.type(), step)) {
            for (Step prerequisite : step.prerequisites()) // Check that no prerequisite is still running.
                try (Lock __ = curator.lock(id.application(), id.type(), prerequisite)) { ; }

            action.accept(new LockedStep(lock, step));
        }
    }

}
