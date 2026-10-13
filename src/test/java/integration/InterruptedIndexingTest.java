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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.OneShotEvent;
import integration.harness.BasicMultiBranchProject;
import java.io.File;
import java.nio.file.Files;
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
            FreeStyleBuild first = master.getBuildByNumber(1);
            SCMSource source = ((BasicMultiBranchProject) master.getParent())
                    .getSCMSources()
                    .get(0);
            if (SCMRevisionAction.getRevision(source, first) == null) { // as a Pipeline's records when it starts
                MockSCMHead head = new MockSCMHead("master");
                first.addAction(
                        new SCMRevisionAction(source, new MockSCMRevision(head, c.getRevision("foo", "master"))));
                first.save();
            }
            SCMRevision built = SCMRevisionAction.getRevision(source, first);
            forgetBuiltRevision(master);

            BasicMultiBranchProject prj = (BasicMultiBranchProject) master.getParent();
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();

            assertThat("not built again", master.getLastBuild().getNumber(), is(1));
            assertThat(
                    Files.readString(prj.getComputation().getLogFile().toPath()),
                    containsString("Recorded " + built + " as built for master"));
            assertThat(prj.getProjectFactory().getRevision(master), is(built));
        }
    }

    @Test
    void buildWithoutItsRevisionIsScheduledAgain(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            FreeStyleProject master = indexedOnce(r, c);
            FreeStyleBuild first = master.getBuildByNumber(1);
            SCMSource source = ((BasicMultiBranchProject) master.getParent())
                    .getSCMSources()
                    .get(0);
            first.removeActions(SCMRevisionAction.class);
            first.save();
            forgetBuiltRevision(master);

            BasicMultiBranchProject prj = (BasicMultiBranchProject) master.getParent();
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();

            assertThat(
                    "nothing says which revision #1 built: as before, the branch builds again",
                    master.getLastBuild().getNumber(),
                    is(2));
        }
    }

    @Test
    void runningAutomaticBuildRecordsItsRevision(JenkinsRule r) throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            BasicMultiBranchProject prj = r.jenkins.createProject(BasicMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(c, "foo", new MockSCMDiscoverBranches())));
            prj.scheduleBuild2(0).getFuture().get();
            Hold.started.block(); // the indexing is done, and #1 of master is running
            FreeStyleProject master = prj.getItem("master");
            FreeStyleBuild first = master.getBuildByNumber(1);
            forgetBuiltRevision(master);
            SCMSource source = prj.getSCMSources().get(0);
            SCMRevision built = new MockSCMRevision(new MockSCMHead("master"), c.getRevision("foo", "master"));
            first.addAction(new SCMRevisionAction(source, built)); // as a Pipeline records when it starts, and saves
            first.save();
            assertThat(prj.getProjectFactory().getRevision(master), is(built));
            Hold.release.signal();
            r.waitUntilNoActivity();
        }
    }

    @TestExtension("runningAutomaticBuildRecordsItsRevision")
    public static final class Hold extends RunListener<Run<?, ?>> {
        static final OneShotEvent started = new OneShotEvent();
        static final OneShotEvent release = new OneShotEvent();

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            started.signal();
            try {
                release.block();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        return master;
    }

    /** As if the indexing had stopped between scheduling the build and recording its revision. */
    private static void forgetBuiltRevision(FreeStyleProject project) throws Exception {
        Files.delete(new File(project.getRootDir(), "scm-revision-hash.xml").toPath());
    }
}
