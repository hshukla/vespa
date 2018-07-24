package com.yahoo.vespa.hosted.controller.deployment;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Steps that make up a deployment job. See {@link JobProfile} for preset profiles.
 *
 * Each step lists its prerequisites; this serves two purposes:
 *
 *   1. A step may only run after its prerequisites, so these define a topological order in which
 *      the steps can be run. Since a job profile may list only a subset of the existing steps,
 *      only the prerequisites of a step which are included in a run's profile will be considered.
 *      Under normal circumstances, a step will run only after each of its prerequisites have succeeded.
 *      When a run has failed, however, each of the always-run steps of the run's profile will be run,
 *      again in a topological order, and again requiring success of all their always-run prerequisites.
 *
 *   2. A step will never run concurrently with its prerequisites. This is to ensure, e.g., that relevant
 *      information from a failed run is stored, and that deployment does not occur after deactivation.
 *
 * @see JobController
 * @author jonmv
 */
public enum Step {

    /** Download and deploy the initial real application, for staging tests. */
    deployInitialReal,

    /** See that the real application has had its nodes converge to the initial state. */
    installInitialReal(deployInitialReal),

    /** Download and deploy real application, restarting services if required. */
    deployReal(installInitialReal),

    /** See that real application has had its nodes converge to the wanted version and generation. */
    installReal(deployReal),

    /** Download test-jar and assemble and deploy tester application. */
    deployTester,

    /** See that tester is done deploying, and is ready to serve. */
    installTester(deployTester),

    /** Ask the tester to run its tests. */
    startTests(installReal, installTester),

    /** See that the tests are done running. */
    endTests(startTests),

    /** Delete the real application -- used for test deployments. */
    deactivateReal(deployInitialReal, deployReal, endTests),

    /** Deactivate the tester. */
    deactivateTester(deployTester, endTests),

    /** Report completion to the deployment orchestration machinery. */
    report(deactivateReal, deactivateTester);


    private final List<Step> prerequisites;

    Step(Step... prerequisites) {
        this.prerequisites = ImmutableList.copyOf(prerequisites);
    }

    public List<Step> prerequisites() { return prerequisites; }


    public enum Status {

        /** Step still has unsatisfied finish criteria -- it may not even have started. */
        unfinished,

        /** Step failed and subsequent steps may not start. */
        failed,

        /** Step succeeded and subsequent steps may now start. */
        succeeded

    }

}