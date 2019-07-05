package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestAPI2;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BitbucketPullRequestResponseValue;

import java.util.Collection;
import java.util.logging.Logger;

/**
 * Created by nishio
 */
public class BitbucketRepository {
    private static final Logger logger = Logger.getLogger(BitbucketRepository.class.getName());
    public static final String BUILD_START_MARKER = "[*BuildStarted* **%s**] %s into %s";
    public static final String BUILD_FINISH_MARKER = "[*BuildFinished* **%s**] %s into %s";

    public static final String BUILD_FINISH_SENTENCE = BUILD_FINISH_MARKER + " \\n\\n **%s** - %s";

    public static final String BUILD_SUCCESS_COMMENT =  "SUCCESS";
    public static final String BUILD_FAILURE_COMMENT = "FAILURE";
    private final BitbucketPullRequestsBuilder builder;
    private BitbucketBuildTrigger trigger;
    private BitbucketApiClient client;
    private Boolean initialized = false;

    public BitbucketRepository(BitbucketPullRequestsBuilder builder) {
        this.builder = builder;
    }

    public void init() {
        if (!initialized) {
            trigger = this.builder.getTrigger();
            client = new BitbucketApiClient(
                    trigger.getUsername(),
                    trigger.getPassword(),
                    trigger.getRepositoryOwner(),
                    trigger.getRepositoryName());
            initialized = true;
        }
    }

    public String postBuildStartCommentTo(BitbucketPullRequestResponseValue pullRequest) {
            String sourceCommit = pullRequest.getSource().getCommit().getHash();
            String destinationCommit = pullRequest.getDestination().getCommit().getHash();
            String comment = String.format(BUILD_START_MARKER, builder.getProject().getDisplayName(), sourceCommit, destinationCommit);
            BitbucketPullRequestAPI2.Comment commentResponse = this.client.postPullRequestComment(pullRequest.getId(), comment);
            return commentResponse.getId().toString();
    }

    public void addFutureBuildTasks(Collection<BitbucketPullRequestResponseValue> pullRequests) {
        for(BitbucketPullRequestResponseValue pullRequest : pullRequests) {
            String commentId = postBuildStartCommentTo(pullRequest);
            if ( this.trigger.getApproveIfSuccess() ) {
                deletePullRequestApproval(pullRequest.getId());
            }
            BitbucketCause cause = new BitbucketCause(
                    pullRequest.getSource().getBranch().getName(),
                    pullRequest.getDestination().getBranch().getName(),
                    pullRequest.getSource().getRepository().getOwnerName(),
                    pullRequest.getSource().getRepository().getRepositoryName(),
                    pullRequest.getId(),
                    pullRequest.getDestination().getRepository().getOwnerName(),
                    pullRequest.getDestination().getRepository().getRepositoryName(),
                    pullRequest.getTitle(),
                    pullRequest.getSource().getCommit().getHash(),
                    pullRequest.getDestination().getCommit().getHash(),
                    commentId);
            this.builder.getTrigger().startJob(cause);
        }
    }

    public void deletePullRequestComment(String pullRequestId, String commentId) {
        this.client.deletePullRequestComment(pullRequestId,commentId);
    }

    public void postFinishedComment(String pullRequestId, String sourceCommit,  String destinationCommit, boolean success, String buildUrl) {
        String message = BUILD_FAILURE_COMMENT;
        if (success){
            message = BUILD_SUCCESS_COMMENT;
        }
        String comment = String.format(BUILD_FINISH_SENTENCE, builder.getProject().getDisplayName(), sourceCommit, destinationCommit, message, buildUrl);

        this.client.postPullRequestComment(pullRequestId, comment);
    }

    public void deletePullRequestApproval(String pullRequestId) {
        this.client.deletePullRequestApproval(pullRequestId);
    }

    public void postPullRequestApproval(String pullRequestId) {
        this.client.postPullRequestApproval(pullRequestId);
    }
}
