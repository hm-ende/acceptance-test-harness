package plugins;

import com.google.inject.Inject;

import org.jenkinsci.test.acceptance.Matcher;
import org.jenkinsci.test.acceptance.Matchers;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.Bug;
import org.jenkinsci.test.acceptance.junit.SmokeTest;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.nodelabelparameter.LabelParameter;
import org.jenkinsci.test.acceptance.plugins.nodelabelparameter.NodeParameter;
import org.jenkinsci.test.acceptance.plugins.textfinder.TextFinderPublisher;
import org.jenkinsci.test.acceptance.po.*;
import org.jenkinsci.test.acceptance.slave.SlaveController;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Feature: Use node name and label as parameter
 * In order to control where a build should run at the time it is triggered
 * As a Jenkins user
 * I want to specify the name of the slave or the label as a build parameter
 */
@WithPlugins("nodelabelparameter")
public class NodeLabelParameterPluginTest extends AbstractJUnitTest {

    @Inject
    SlaveController slave;

    @Inject
    SlaveController slave2;

    /**
     * Scenario: Build on a particular slave
     * Given I have installed the "nodelabelparameter" plugin
     * And a job
     * And a slave named "slave42"
     * When I configure the job
     * And I add node parameter "slavename"
     * And I save the job
     * And I build the job with parameter
     * | slavename | slave42 |
     * Then the build should run on "slave42"
     */
    @Test
    @Category(SmokeTest.class)
    public void build_on_a_particular_slave() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s = slave.install(jenkins).get();
        j.configure();
        j.addParameter(NodeParameter.class).setName("slavename");
        j.save();

        Build b = j.startBuild(singletonMap("slavename", s.getName())).shouldSucceed();
        assertThat(b.getNode(), is(s.getName()));
    }

    /**
     * This test is intended to check that an online slave is ignored
     * when selected for a job and the job is configured with "Node eligibility" setting
     * is set to "Ignore Offline Nodes"
     * <p/>
     * It is expected that all nodes are available for the build job.
     * The default node shall be preselected and the job shall be built on the available slave.
     */
    @Test
    public void build_with_preselected_node() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s = slave.install(jenkins).get();
        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.defaultNodes.select(s.getName());
        p.allowedNodes.select("ALL (no restriction)");
        p.disallowMultiple.check();

        j.save();

        visit(j.getBuildUrl());
        assertThat("Default node is selected", find(by.option(s.getName())).isSelected(), is(true));

        WebElement wbElement = find(by.xpath("//select[@name='labels']"));
        List<WebElement> availableNodes = wbElement.findElements(by.tagName("option"));

        assertThat("master", is(availableNodes.get(0).getText()));
        assertThat(s.getName(), is(availableNodes.get(1).getText()));
        Build b = j.startBuild(singletonMap("slavename", s.getName())).shouldSucceed();
        assertThat(b.getNode(), is(s.getName()));
    }

    /**
     * Scenario: Run on label
     * Given I have installed the "nodelabelparameter" plugin
     * And a job
     * And a slave named "slave42"
     * And a slave named "slave43"
     * When I configure the job
     * And I add label parameter "slavelabel"
     * And I save the job
     * And I build the job with parameter
     * | slavelabel | slave42 |
     * Then the build should run on "slave42"
     * And I build the job with parameter
     * | slavelabel | !slave42 && !slave43 |
     * Then the build should run on "master"
     */
    @Test
    public void run_on_label() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s1 = slave.install(jenkins).get();
        Slave s2 = slave2.install(jenkins).get();

        j.configure();
        j.addParameter(LabelParameter.class).setName("slavelabel");
        j.save();

        Build b = j.startBuild(singletonMap("slavelabel", s1.getName())).shouldSucceed();
        assertThat(b.getNode(), is(s1.getName()));

        b = j.startBuild(singletonMap("slavelabel", String.format("!%s && !%s", s1.getName(), s2.getName()))).shouldSucceed();
        assertThat(b.getNode(), is("master"));
    }

    /**
     * Scenario: Run on several slaves
     * Given I have installed the "nodelabelparameter" plugin
     * And a job
     * And a slave named "slave42"
     * When I configure the job
     * And I add node parameter "slavename"
     * And I allow multiple nodes
     * And I enable concurrent builds
     * And I save the job
     * And I build the job with parameter
     * | slavename | slave42, master |
     * Then the job should have 2 builds
     * And  the job should be built on "master"
     * And  the job should be built on "slave42"
     */
    @Test
    public void run_on_several_slaves() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s = slave.install(jenkins).get();

        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.allowMultiple.check();
        j.concurrentBuild.check();
        j.save();

        j.startBuild(singletonMap("slavename", s.getName() + ",master")).shouldSucceed();

        assertThat(j.getNextBuildNumber(), is(3));

        j.getLastBuild().waitUntilFinished();

        j.shouldHaveBuiltOn(jenkins);
        j.shouldHaveBuiltOn(s);
    }

    /**
     * This test is intended to check that an offline slave is not ignored
     * when selected for a job and the job is configured with "Node eligibility" setting
     * is set to "All Nodes"
     * <p/>
     * It is expected that the job is pending due to the offline status of the slave.
     * But it will be reactivated as soon as the slave status becomes online.
     */
    @Test
    public void run_on_a_particular_offline_slave() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s = slave.install(jenkins).get();

        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.disallowMultiple.check();
        j.save();

        //as the slave has been started after creation, we have to take it down again
        s.markOffline();
        assertThat(s.isOffline(), is(true));

        //use scheduleBuild instead of startBuild to avoid a timeout waiting for Build being started
        Build b = j.scheduleBuild(singletonMap("slavename", s.getName()));
        sleep(3000);    // TODO: not the best way to wait for the scheduled job to go through the queue, but a bit of wait is needed
        shouldBeTriggeredWithoutOnlineNode(b, s.getName());

        //bring the slave up again, the Build should start immediately
        s.markOnline();
        assertThat(s.isOnline(), is(true));

        b.waitUntilFinished();
        j.shouldHaveBuiltOn(s);
    }

    /**
     * This test is intended to check that an offline slave is ignored
     * when selected for a job and the job is configured with "Node eligibility" setting
     * is set to "Ignore Offline Nodes"
     * <p/>
     * It is expected that the job is pending due no valid slave is available.
     */
    @Test
    public void run_on_a_particular_offline_slave_with_ignore() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s = slave.install(jenkins).get();
        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.disallowMultiple.check();
        p.eligibility.select("Ignore Offline Nodes");

        j.save();

        //as the slave has been started after creation, we have to take it down again
        s.markOffline();
        assertThat(s.isOffline(), is(true));

        //use scheduleBuild instead of startBuild to avoid a timeout waiting for Build being started
        Build b = j.scheduleBuild(singletonMap("slavename", s.getName()));
        sleep(3000);    // TODO: not the best way to wait for the scheduled job to go through the queue, but a bit of wait is needed
        shouldBeTriggeredWithoutValidOnlineNode(b, s.getName());
    }

    /**
     * This test is intended to check that an offline slave is not ignored
     * when selected for a job and the job is configured with "Node eligibility" setting
     * is set to "All Nodes" in combination with "Allow multiple nodes" option.
     * <p/>
     * The job shall run on a mixed configuration of online and offline slaves.
     * It is expected that a number of builds is created equivalent to the number of
     * slaves selected. The build shall be pending for the offline slaves and executed
     * successfully for the online slaves.
     * Pending builds will be reactivated as soon as the particular slave becomes online.
     */
    @Test
    public void run_on_several_online_and_offline_slaves() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s1 = slave.install(jenkins).get();
        Slave s2 = slave.install(jenkins).get();

        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.allowMultiple.check();
        j.concurrentBuild.check();

        j.save();

        //as both slaves have been started after creation, we have to take one of them down
        s2.markOffline();
        assertThat(s2.isOnline(), is(false));
        assertThat(s1.isOnline(), is(true));

        //select both slaves for this build
        Build b = j.startBuild(singletonMap("slavename", s1.getName() + "," + s2.getName())).shouldSucceed();

        //ensure that the build on the online slave has been done
        assertThat(j.getLastBuild().waitUntilFinished().getNumber(), is(equalTo(1)));
        j.shouldHaveBuiltOn(s1);

        assertThat(j.open(), Matchers.hasContent(s2.getName() + " is offline"));

        //bring second slave online again
        s2.markOnline();
        assertThat(s2.isOnline(), is(true));

        assertThat(j.getLastBuild().waitUntilFinished().getNumber(), is(equalTo(2)));
        j.shouldHaveBuiltOn(s2);
    }

    /**
     * This test is intended to check that an offline slave is ignored
     * when selected for a job and the job is configured with "Node eligibility" setting
     * is set to "Ignore offline Nodes" in combination with "Allow multiple nodes" option.
     * <p/>
     * The job shall run on a mixed configuration of online slaves.
     * It is expected that a number of builds is created equivalent to the number of
     * slaves selected. The build shall be pending as there is no valid online slave.
     * Pending builds will be reactivated as soon as the particular slave becomes online.
     */
    @Test
    public void pending_build_with_no_valid_node() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        Slave s1 = slave.install(jenkins).get();
        Slave s2 = slave.install(jenkins).get();

        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.allowMultiple.check();
        j.concurrentBuild.check();
        p.eligibility.select("Ignore Offline Nodes");

        j.save();

        //as both slaves have been started after creation, we have to take one of them down
        s2.markOffline();
        assertThat(s2.isOffline(), is(true));
        assertThat(s1.isOnline(), is(true));

        //select both slaves for this build
        Build b = j.startBuild(singletonMap("slavename", s1.getName()));

        // wait for the build on slave 1 to finish
        b.waitUntilFinished();

        //ensure that the build on the online slave has been done
        j.shouldHaveBuiltOn(s1);

        //use scheduleBuild instead of startBuild to avoid a timeout waiting for Build being started
        b = j.scheduleBuild(singletonMap("slavename", s2.getName()));

        waitFor(by.href("/queue/cancelItem?id=2")); // shown in queue
        sleep(10000); // after some time
        shouldBeTriggeredWithoutValidOnlineNode(b, s2.getName());
    }

    /**
     * This test is intended to verify that the second build is not started when the
     * build already failed on the first slave in combination with the
     * "run next build only if build succeeds" setting of the node parameter.
     * <p/>
     * As a build can fail in different stages this test simulates a FAILED result
     * during the main build action.
     */
    @Test
    public void trigger_if_succeeds_with_failed_main_build() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        ArrayList<Node> slaves = new ArrayList<>();

        slaves.add(slave.install(jenkins).get());
        slaves.add(slave.install(jenkins).get());

        j.configure();

        // set up the node parameter
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.runIfSuccess.check();

        //ensure the main build fails by using a shell exit command
        j.addShellStep("exit 1");

        j.save();

        // select both slaves for this build
        j.startBuild(singletonMap("slavename", slaves.get(0).getName() + "," + slaves.get(1).getName()))
                .shouldFail();

        // verify failed result prevents the job to be built on further nodes.
        // As the nodes get random names and the selected nodes are utilized in alphabetical order
        // of their names, the first build will not necessarily be done on s1. Thus, it can only
        // be verified that the job has been built on one of the slaves.
        j.shouldHaveBuiltOnOneOfNNodes(slaves);

        assertThat(j.getNextBuildNumber(), is(2));

    }

    /**
     * This test is intended to verify that the second build is not started when the
     * build already failed on the first slave in combination with the
     * "run next build only if build succeeds" setting of the node parameter.
     * <p/>
     * As a build can fail in different stages this test simulates a FAILED result
     * during the post build step. Therefore the text-finder plugin is used to
     * fail the build based on a simple pattern matching with a text file copied to
     * the slave's workspace.
     * <p/>
     * Note that in this case the main build action is still completed with status SUCCESS.
     */
    @Test
    @WithPlugins("text-finder")
    @Bug("23129")
    @Ignore("Until JENKINS-23129 is fixed")
    public void trigger_if_succeeds_with_failed_post_build_step() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        ArrayList<Node> slaves = new ArrayList<>();

        slaves.add(slave.install(jenkins).get());
        slaves.add(slave.install(jenkins).get());

        j.configure();

        // set up the node parameter
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.runIfSuccess.check();

        // copy the file to mark the status
        j.copyResource(resource("/textfinder_plugin/textfinder-result_failed.log"));

        // set up the post build action
        TextFinderPublisher tf = j.addPublisher(TextFinderPublisher.class);
        tf.filePath.sendKeys("textfinder-result_failed.log");
        tf.regEx.sendKeys("^RESULT=SUCCESS$");
        tf.succeedIfFound.click();

        j.save();

        // select both slaves for this build
        j.startBuild(singletonMap("slavename", slaves.get(0).getName() + "," + slaves.get(1).getName()))
                .shouldFail();

        // verify failed result prevents the job to be built on further nodes.
        // As the nodes get random names and the selected nodes are utilized in alphabetical order
        // of their names, the first build will not necessarily be done on s1. Thus, it can only
        // be verified that the job has been built on one of the slaves.
        j.startBuild(singletonMap("slavename", slaves.get(0).getName() + "," + slaves.get(1).getName()))
                .shouldFail();

        assertThat(j.getNextBuildNumber(), is(2));

    }

    /**
     * This test is intended to verify that the second build is not started when the
     * build already deemed unstable on the first slave in combination with the
     * "run next build only if build succeeds" setting of the node parameter.
     * <p/>
     * The JUnit test publisher is used to create an unstable build during the post
     * build step.
     * <p/>
     * Note that in this case the main build action is still completed with status SUCCESS.
     */
    @Test
    @Bug("23129")
    @Ignore("Until JENKINS-23129 is fixed")
    public void trigger_if_succeeds_with_unstable_post_build_step() throws Exception {
        FreeStyleJob j = jenkins.jobs.create();

        ArrayList<Node> slaves = new ArrayList<>();

        slaves.add(slave.install(jenkins).get());
        slaves.add(slave.install(jenkins).get());

        j.configure();

        // set up the node parameter
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");
        p.runIfSuccess.check();

        // copy the unit test results
        j.copyResource(resource("/junit/failure/com.simple.project.AppTest.txt"));
        j.copyResource(resource("/junit/failure/TEST-com.simple.project.AppTest.xml"));

        // add the post build step
        j.addPublisher(JUnitPublisher.class).testResults.set("*.xml");
        j.save();

        // select both slaves for this build
        j.startBuild(singletonMap("slavename", slaves.get(0).getName() + "," + slaves.get(1).getName()))
                .shouldFail();

        // verify unstable result prevents the job to be built on further nodes.
        // As the nodes get random names and the selected nodes are utilized in alphabetical order
        // of their names, the first build will not necessarily be done on s1. Thus, it can only
        // be verified that the job has been built on one of the slaves.
        j.startBuild(singletonMap("slavename", slaves.get(0).getName() + "," + slaves.get(1).getName()))
                .shouldFail();

        assertThat(j.getNextBuildNumber(), is(2));
    }

    /**
     * This test is intended to check that two created slaves are added to the
     * node restriction box. Additionally, when selecting a slave and master as
     * nodes in the restriction panel, only those should be available for the
     * build.
     */
    @Test
    public void run_on_online_slave_and_master_with_node_restriction() throws Exception {
        Slave s1 = slave.install(jenkins).get();
        Slave s2 = slave.install(jenkins).get();

        FreeStyleJob j = jenkins.jobs.create();

        j.configure();
        NodeParameter p = j.addParameter(NodeParameter.class);
        p.setName("slavename");

        // master, ALL + 2 slaves
        assertThat("Amount of possible nodes does not match", p.getPossibleNodesOptions().size(), is(4));

        p.allowMultiple.check();
        p.allowedNodes.select("master");
        p.allowedNodes.select(s1.getName());

        j.concurrentBuild.check();
        j.save();

        assertThat(s1.isOnline(), is(true));
        assertThat(s2.isOnline(), is(true));

        //checks that build selection box only contains the possible nodes
        visit(j.getBuildUrl());
        List<String> slaves = p.applicableNodes();
        assertThat("Amount of selectable nodes", slaves.size(), is(2));
        assertThat(slaves, containsInAnyOrder("master", s1.getName()));

        j.startBuild(singletonMap("slavename", s1.getName() + ",master"));

        j.getLastBuild().waitUntilFinished();

        j.shouldHaveBuiltOn(jenkins);
        j.shouldHaveBuiltOn(s1);

        assertThat(j.getNextBuildNumber(), is(3));
    }

    private void shouldBeTriggeredWithoutOnlineNode(Build build, String nodename) {
        String pendingBuildText = getPendingBuildText(build);

        assertThat(pendingBuildText, containsString(nodename + " is offline"));
        assertThat(build, not(started()));
    }

    /**
     * This function tries to assert that the current build is pending due there are no
     * valid online nodes. The node's name has to be specified when calling this method.
     */
    private void shouldBeTriggeredWithoutValidOnlineNode(Build build, String nodename) {
        String pendingBuildText = getPendingBuildText(build);

        assertThat(pendingBuildText, isPending(nodename));
        assertThat(build, not(started()));
    }

    private Matcher<Build> started() {
        return new Matcher<Build>("Build has started") {
            @Override
            public boolean matchesSafely(Build build) {
                return build.hasStarted();
            }
        };
    }

    private org.hamcrest.Matcher<String> isPending(String nodename) {
        return allOf(
                containsString("Job triggered without a valid online node, given where: " + nodename),
                containsString("pending")
        );
    }

    /**
     * Get pending build message in build history summary if there is one.
     */
    private String getPendingBuildText(Build build) {
        //ensure to be on the job's page otherwise we do not have the build history summary
        // to get their content
        build.job.open();

        // pending message comes from the queue, and queue's maintenance is asynchronous to UI threads.
        // so if the original response doesn't contain it, we have to wait for the refersh of the build history.
        // so give it a bigger wait.
        return find(by.xpath("//img[@alt='pending']/../..")).getText();
    }
}
