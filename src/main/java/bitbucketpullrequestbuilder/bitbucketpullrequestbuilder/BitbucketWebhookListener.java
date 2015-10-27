package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValue;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValueRepositoryRepository;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
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
            if (body.startsWith("payload=")) {
                body = body.substring(8);
            }

            LOGGER.log(Level.INFO, "Received hook notification : {0}", body);

            if (isPullRequestCreateOrUpdate(req)) {
                JSONObject payload = JSONObject.fromObject(body);

                triggerBuildForPullRequest(payload);
            } else if (isPush(req)) {
                JSONObject payload = JSONObject.fromObject(body);

                triggerBuildForDestinationBranchPush(payload);
            }

        } else {
            LOGGER.log(Level.WARNING, "The Jenkins job cannot be triggered. You might not have configured the WebHook correctly on BitBucket `http://<JENKINS-URL>/bitbucket-pullrequest`");
        }

    }

    private static boolean isPush(StaplerRequest req) {
        return req.getHeader("User-Agent").equalsIgnoreCase("Bitbucket-Webhooks/2.0") &&
                req.getHeader("X-Event-Key").equalsIgnoreCase("repo:push");
    }

    private static boolean isPullRequestCreateOrUpdate(HttpServletRequest req) {
        return req.getHeader("User-Agent").equalsIgnoreCase("Bitbucket-Webhooks/2.0") &&
                (req.getHeader("X-Event-Key").equalsIgnoreCase("pullrequest:created") ||
                        req.getHeader("X-Event-Key").equalsIgnoreCase("pullrequest:updated"));
    }

    private static void triggerBuildForPullRequest(JSONObject payload) {
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

    private static void triggerBuildForDestinationBranchPush(JSONObject payload) {
        Set<String> destinationBranches = getDestinationBranches(payload);

        LOGGER.log(Level.FINE, "Destination branches for push: {0}", destinationBranches);

        if (!destinationBranches.isEmpty()) {
            try {
                BitbucketPullRequestResponseValueRepositoryRepository repository = createRepositoryFromJson(payload);
                HashSet<String> pullRequestsBuilt = new HashSet<String>();

                for (Job<?,?> job : getAllJobs()) {
                    if (repositoryMatches(repository.getLinks().getHtml().getHref(), job)) {

                        LOGGER.log(Level.FINE, "Push repository matches job: {0}", job.getName());

                        for (BitbucketBuildTrigger trigger : getBitBucketTriggers(job)) {
                            if (trigger.getCheckDestinationCommit()) {
                                for (BitbucketPullRequestResponseValue pullRequest : getOpenPullRequests(trigger)) {

                                    LOGGER.log(Level.INFO, "Evaluating open pull request: {0}", pullRequest.getTitle());

                                    if (!isSkipBuild(pullRequest.getTitle(), trigger) &&
                                            destinationBranches.contains(pullRequest.getDestination().getBranch().getName()) &&
                                            pullRequestsBuilt.add(pullRequest.getLinks().getSelf().getHref())) {
                                        trigger.getBuilder().runPullRequestBuild(pullRequest);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Could not parse JSON for repo push (" + e.getMessage() + "):\n" + payload.toString(4));
            }
        }
    }

    private static Set<String> getDestinationBranches(JSONObject repositoryPushPayload) {
        HashSet<String> destinationBranches = new HashSet<String>();

        for (Object obj:repositoryPushPayload.getJSONObject("push")
                .getJSONArray("changes")) {
            if (obj instanceof JSONObject) {
                JSONObject change = (JSONObject)obj;

                if (change.has("new"))
                {
                    JSONObject newState = change.getJSONObject("new");

                    if (newState.getString("type").equals("branch")) {
                        destinationBranches.add(newState.getString("name"));
                    }
                }
            }
        }

        return destinationBranches;
    }

    private static List<Job> getAllJobs() {
        return Jenkins.getInstance().getAllItems(Job.class);
    }

    private static Iterable<? extends BitbucketPullRequestResponseValue> getOpenPullRequests(BitbucketBuildTrigger trigger) {
        BitbucketApiClient apiClient = new BitbucketApiClient(trigger.getUsername(), trigger.getPassword(), trigger.getRepositoryOwner(), trigger.getRepositoryName());
        ArrayList<BitbucketPullRequestResponseValue> pullRequests = new ArrayList<BitbucketPullRequestResponseValue>();

        for (BitbucketPullRequestResponseValue pullRequest : apiClient.getPullRequests()) {
            if (pullRequest.getState().equalsIgnoreCase("open")) {
                pullRequests.add(pullRequest);
            }
        }

        return pullRequests;
    }

    private static Collection<BitbucketBuildTrigger> findTriggersMatchingPullRequest(BitbucketPullRequestResponseValue pullRequest) {
        List<BitbucketBuildTrigger> triggers = new ArrayList<BitbucketBuildTrigger>();

        for (Job<?,?> job : getAllJobs()) {
            if(repositoryMatches(pullRequest, job)) {
                for (BitbucketBuildTrigger trigger : getBitBucketTriggers(job)) {
                    if (!isSkipBuild(pullRequest.getTitle(), trigger)) {
                        triggers.add(trigger);
                    }
                }
            }
        }

        return triggers;
    }

    private static Boolean repositoryMatches(BitbucketPullRequestResponseValue pullRequest, Job<?, ?> job) {
        return repositoryMatches(pullRequest.getDestination().getRepository().getLinks().getHtml().getHref(), job);
    }

    private static Boolean repositoryMatches(String gitRepositoryUri, Job<?, ?> job) {
        try {
            URIish gitRepository = new URIish(gitRepositoryUri);
            SCMTriggerItem item = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job);

            for (SCM scmTrigger : item.getSCMs()) {
                if (scmTrigger instanceof GitSCM) {
                    for (RemoteConfig remoteConfig : ((GitSCM) scmTrigger).getRepositories()) {
                        for (URIish urIish : remoteConfig.getURIs()) {
                            if (GitStatus.looselyMatches(urIish, gitRepository)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.SEVERE, "Could not parse repository URL: " + e.getMessage());
            return false;
        }

        return false;
    }

    private static Collection<BitbucketBuildTrigger> getBitBucketTriggers(Job<?, ?> job) {
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

    private static BitbucketPullRequestResponseValueRepositoryRepository createRepositoryFromJson(JSONObject payload) throws IOException {
        JSONObject repositoryJson = payload.getJSONObject("repository");

        if (repositoryJson == null) {
            throw new IOException("Did not find repository object in payload.");
        }

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(repositoryJson.toString(), BitbucketPullRequestResponseValueRepositoryRepository.class);
    }

    private static BitbucketPullRequestResponseValue createPullRequestFromJson(JSONObject payload) throws IOException {
        JSONObject pullRequestJson = payload.getJSONObject("pullrequest");

        if (pullRequestJson == null) {
            throw new IOException("Did not find pull request object in payload.");
        }

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(pullRequestJson.toString(), BitbucketPullRequestResponseValue.class);
    }

    private static boolean isSkipBuild(String pullRequestTitle, BitbucketBuildTrigger trigger) {
        String skipPhrases = trigger.getCiSkipPhrases();
        if (skipPhrases != null && !"".equals(skipPhrases)) {
            String[] phrases = skipPhrases.split(",");
            for(String phrase : phrases) {
                if (pullRequestTitle.toLowerCase().contains(phrase.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Logger LOGGER = Logger.getLogger(BitbucketWebhookListener.class.getName());
}
