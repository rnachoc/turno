package io.github.rnachoc.turno.core.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much each caller may ask of the core per minute.
 *
 * @param agentsPerMinute requests with the agents' token
 * @param staffPerMinute requests with the staff's token
 * @param workflowsPerMinute requests with the worker's token
 * @param failedAuthenticationsPerMinute wrong tokens from one address before it has to wait
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@ConfigurationProperties("turno.rate-limit")
public record RateLimitProperties(
        int agentsPerMinute, int staffPerMinute, int workflowsPerMinute, int failedAuthenticationsPerMinute) {

    /** Refuses to start with a limit that would stop everything or nothing. */
    public RateLimitProperties {
        if (agentsPerMinute < 1 || staffPerMinute < 1 || workflowsPerMinute < 1 || failedAuthenticationsPerMinute < 1) {
            throw new IllegalArgumentException("Every limit must be at least one request per minute");
        }
    }

    /**
     * Finds the limit of a caller.
     *
     * @param principal the caller
     * @return its requests per minute
     */
    int perMinute(ApiPrincipal principal) {
        return switch (principal) {
            case AGENTS -> agentsPerMinute;
            case STAFF -> staffPerMinute;
            case WORKFLOWS -> workflowsPerMinute;
        };
    }
}
