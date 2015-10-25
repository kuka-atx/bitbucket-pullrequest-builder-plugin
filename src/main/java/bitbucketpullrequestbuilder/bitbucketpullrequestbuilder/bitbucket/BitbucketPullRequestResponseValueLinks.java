package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * Created by charles on 10/24/15.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketPullRequestResponseValueLinks {
    private BitbucketPullRequestResponseValueLink html;
    private BitbucketPullRequestResponseValueLink avatar;
    private BitbucketPullRequestResponseValueLink self;

    public BitbucketPullRequestResponseValueLink getSelf() {
        return self;
    }

    public void setSelf(BitbucketPullRequestResponseValueLink self) {
        this.self = self;
    }

    public BitbucketPullRequestResponseValueLink getAvatar() {
        return avatar;
    }

    public void setAvatar(BitbucketPullRequestResponseValueLink avatar) {
        this.avatar = avatar;
    }

    public BitbucketPullRequestResponseValueLink getHtml() {
        return html;
    }

    public void setHtml(BitbucketPullRequestResponseValueLink html) {
        this.html = html;
    }
}
