package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * Created by charles on 10/24/15.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketPullRequestResponseValueLink {
    private String href;

    public String getHref() { return this.href; }

    public void setHref(String href) { this.href = href; }
}
