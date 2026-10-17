/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins project contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package integration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.OneShotEvent;
import integration.harness.BasicMultiBranchProject;
import java.io.File;
import java.nio.file.Files;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMHead;
import jenkins.scm.impl.mock.MockSCMRevision;
import jenkins.scm.impl.mock.MockSCMSource;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An indexing that scheduled a branch's build but stopped before recording its revision (its controller died, or saving
 * failed) must not leave the next indexing to build the branch again.
 */
@WithJenkins
class InterruptedIndexingTest {

    @Test
    void automaticBuildWithItsRevisionIsNotScheduledAgain(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            FreeStyleProject master = indexedOnce(r, c);
            SCMRevision built = attachRevision(c, master.getBuildByNumber(1));
            forgetBuiltRevision(master);

            reindex(r, master);

            assertThat("not built again", master.getLastBuild().getNumber(), is(1));
            assertThat(factory(master).getRevision(master), is(built));
        }
    }

    @Test
    void buildWithoutItsRevisionIsScheduledAgain(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            FreeStyleProject master = indexedOnce(r, c);
            FreeStyleBuild first = master.getBuildByNumber(1);
            first.removeActions(SCMRevisionAction.class);
            first.save();
            forgetBuiltRevision(master);

            reindex(r, master);

            assertThat(
                    "nothing says which revision #1 built: as before, the branch builds again",
                    master.getLastBuild().getNumber(),
                    is(2));
        }
    }

    @Test
    void userTriggeredBuildIsNotTakenAsBuilt(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            FreeStyleProject master = indexedOnce(r, c);
            FreeStyleBuild second = r.buildAndAssertSuccess(master);
            attachRevision(c, second);
            forgetBuiltRevision(master);

            reindex(r, master);

            assertThat(
                    "the last build was not scheduled by indexing: the branch builds again",
                    master.getLastBuild().getNumber(),
                    is(3));
        }
    }

    @Test
    void runningAutomaticBuildRecordsItsRevision(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            BasicMultiBranchProject prj = r.jenkins.createProject(BasicMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(c, "foo", new MockSCMDiscoverBranches())));
            prj.scheduleBuild2(0).getFuture().get();
            HoldFirst hold = r.jenkins.getExtensionList(RunListener.class).get(HoldFirst.class);
            hold.started.block(); // the indexing is done, and #1 of master is running
            FreeStyleProject master = prj.getItem("master");
            forgetBuiltRevision(master);

            SCMRevision built = attachRevision(c, master.getBuildByNumber(1)); // as a Pipeline does when it starts

            assertThat(factory(master).getRevision(master), is(built));
            hold.release.signal();
            r.waitUntilNoActivity();
        }
    }

    @Test
    void runningUserTriggeredBuildDoesNotRecordItsRevision(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            FreeStyleProject master = indexedOnce(r, c);
            HoldSecond hold = r.jenkins.getExtensionList(RunListener.class).get(HoldSecond.class);
            master.scheduleBuild2(0, new Cause.UserIdCause());
            hold.started.block(); // #2 of master is running
            forgetBuiltRevision(master);

            attachRevision(c, master.getBuildByNumber(2));

            assertThat(factory(master).getRevision(master), nullValue());
            hold.release.signal();
            r.waitUntilNoActivity();
        }
    }

    /** Holds one build of the branch at its start, until released. */
    private abstract static class Hold extends RunListener<Run<?, ?>> {
        final OneShotEvent started = new OneShotEvent();
        final OneShotEvent release = new OneShotEvent();
        private final int number;

        Hold(int number) {
            this.number = number;
        }

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            if (run.getNumber() != number) {
                return;
            }
            started.signal();
            try {
                release.block();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestExtension("runningAutomaticBuildRecordsItsRevision")
    public static final class HoldFirst extends Hold {
        public HoldFirst() {
            super(1);
        }
    }

    @TestExtension("runningUserTriggeredBuildDoesNotRecordItsRevision")
    public static final class HoldSecond extends Hold {
        public HoldSecond() {
            super(2);
        }
    }

    private static FreeStyleProject indexedOnce(JenkinsRule r, MockSCMController c) throws Exception {
        c.createRepository("foo");
        BasicMultiBranchProject prj = r.jenkins.createProject(BasicMultiBranchProject.class, "foo");
        prj.getSourcesList().add(new BranchSource(new MockSCMSource(c, "foo", new MockSCMDiscoverBranches())));
        prj.scheduleBuild2(0).getFuture().get();
        r.waitUntilNoActivity();
        FreeStyleProject master = prj.getItem("master");
        assertThat(master, notNullValue());
        assertThat(master.getLastBuild().getNumber(), is(1));
        assertThat(factory(master).getRevision(master), notNullValue());
        return master;
    }

    private static void reindex(JenkinsRule r, FreeStyleProject branch) throws Exception {
        ((BasicMultiBranchProject) branch.getParent())
                .scheduleBuild2(0)
                .getFuture()
                .get();
        r.waitUntilNoActivity();
    }

    private static BranchProjectFactory<FreeStyleProject, FreeStyleBuild> factory(FreeStyleProject branch) {
        return ((BasicMultiBranchProject) branch.getParent()).getProjectFactory();
    }

    /**
     * Attaches the revision of the branch's head to a build, replacing any, and saves it, as a Pipeline does when it
     * starts.
     */
    private static SCMRevision attachRevision(MockSCMController c, Run<?, ?> build) throws Exception {
        SCMSource source = ((BasicMultiBranchProject) build.getParent().getParent())
                .getSCMSources()
                .get(0);
        SCMRevision revision = new MockSCMRevision(new MockSCMHead("master"), c.getRevision("foo", "master"));
        build.removeActions(SCMRevisionAction.class);
        build.addAction(new SCMRevisionAction(source, revision));
        build.save();
        return revision;
    }

    /** As if the indexing had stopped between scheduling the build and recording its revision. */
    private static void forgetBuiltRevision(FreeStyleProject project) throws Exception {
        Files.delete(new File(project.getRootDir(), "scm-revision-hash.xml").toPath());
    }
}
