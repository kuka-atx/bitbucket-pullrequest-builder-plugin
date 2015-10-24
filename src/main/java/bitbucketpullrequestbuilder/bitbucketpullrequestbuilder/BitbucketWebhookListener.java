package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponse;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValue;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class BitbucketWebhookListener  implements UnprotectedRootAction {
    private final String BITBUCKET_HOOK_URL = "bitbucket-pullrequest";

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return null;
    }

    public String getUrlName() {
        return BITBUCKET_HOOK_URL;
    }

    public void doIndex(StaplerRequest req) throws IOException {
        String body = IOUtils.toString(req.getInputStream());
        if (!body.isEmpty() && req.getRequestURI().contains("/" + BITBUCKET_HOOK_URL)) {

            String contentType = req.getContentType();

            if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
                body = URLDecoder.decode(body);
            }

            if (body.startsWith("payload=")) {
                body = body.substring(8);
            }

            LOGGER.log(Level.WARNING, "Received hook notification : {0}", body);

            if (isPullRequestCreateOrUpdate(req)) {
                JSONObject payload = JSONObject.fromObject(body);

                triggerAppropriateBuild(req, payload);
            }

        } else {
            LOGGER.log(Level.WARNING, "The Jenkins job cannot be triggered. You might not have configured the WebHook correctly on BitBucket !! `http://<JENKINS-URL>/bitbucket-pullrequest`");
        }

    }

    private void triggerAppropriateBuild(HttpServletRequest req, JSONObject payload) {
        try {
            BitbucketPullRequestResponseValue pullRequest = createPullRequestFromJson(payload);

            LOGGER.log(Level.FINE, "Parsed pull request: {0}", pullRequest.getDescription());

            Collection<BitbucketBuildTrigger> matchingTriggers = findTriggersMatchingPullRequest(pullRequest);

            for(BitbucketBuildTrigger trigger : matchingTriggers) {
                trigger.getBuilder().runPullRequestBuild(pullRequest);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not parse JSON for pull request (" + e.getMessage() + "):\n" + payload.toString(4));
        }
    }

    private Collection<BitbucketBuildTrigger> findTriggersMatchingPullRequest(BitbucketPullRequestResponseValue pullRequest) {
        List<BitbucketBuildTrigger> triggers = new ArrayList<BitbucketBuildTrigger>();

        for (Job<?,?> job : Jenkins.getInstance().getAllItems(Job.class)) {
            if(repositoryMatches(pullRequest, job)) {
                for (BitbucketBuildTrigger trigger : getBitBucketTriggers(job)) {
                    triggers.add(trigger);
                }
            }
        }

        return triggers;
    }

    private Boolean repositoryMatches(BitbucketPullRequestResponseValue pullRequest, Job<?, ?> job) {
//        SCMTriggerItem item = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job);
//        List<SCM> scmTriggered = new ArrayList<SCM>();
//        URIish gitRemote = new URIish(pullRequest.getDestination().getRepository().get)
//        for (SCM scmTrigger : item.getSCMs()) {
//
//        }
        return false;
    }

    private Collection<BitbucketBuildTrigger> getBitBucketTriggers(Job<?, ?> job) {
        ArrayList<BitbucketBuildTrigger> triggers = new ArrayList<BitbucketBuildTrigger>();

        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            ParameterizedJobMixIn.ParameterizedJob pJob = (ParameterizedJobMixIn.ParameterizedJob) job;

            for (Trigger trigger : pJob.getTriggers().values()) {
                if (trigger instanceof BitbucketBuildTrigger) {
                    triggers.add((BitbucketBuildTrigger) trigger);
                }
            }
        }

        LOGGER.log(Level.FINE, "Found {0} applicable triggers", triggers.size());

        return triggers;
    }

    private BitbucketPullRequestResponseValue createPullRequestFromJson(JSONObject payload) throws IOException {
        JSONObject pullRequestJson = payload.getJSONObject("pullrequest");

        if (pullRequestJson == null) {
            throw new IOException("Did not find pull request object in payload.");
        }

        ObjectMapper mapper = new ObjectMapper();
        BitbucketPullRequestResponseValue pullRequest = mapper.readValue(pullRequestJson.toString(), BitbucketPullRequestResponseValue.class);
        return pullRequest;
    }

    private static boolean isPullRequestCreateOrUpdate(HttpServletRequest req) {
        return req.getHeader("User-Agent").equalsIgnoreCase("Bitbucket-Webhooks/2.0") &&
                (req.getHeader("X-Event-Key").equalsIgnoreCase("pullrequest:created") ||
                        req.getHeader("X-Event-Key").equalsIgnoreCase("pullrequest:updated"));
    }

    private static final Logger LOGGER = Logger.getLogger(BitbucketWebhookListener.class.getName());
}
