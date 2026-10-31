package io.github.rnachoc.turno.core.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API tokens. The service refuses to start with a missing, short or shared token, and the messages
 * never include their values.
 *
 * @param agentsToken token of the agents service
 * @param staffToken token of the staff console
 * @param workflowsToken token of the workflow worker
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@ConfigurationProperties("turno.security")
public record SecurityProperties(String agentsToken, String staffToken, String workflowsToken) {

    private static final int MIN_LENGTH = 32;

    /** Validates the tokens. */
    public SecurityProperties {
        requireUsable(agentsToken, "agents");
        requireUsable(staffToken, "staff");
        requireUsable(workflowsToken, "workflows");
        // Not Set.of, whose error for a duplicate would print the token.
        if (agentsToken.equals(staffToken)
                || agentsToken.equals(workflowsToken)
                || staffToken.equals(workflowsToken)) {
            throw new IllegalArgumentException("Each API token must be different");
        }
    }

    private static void requireUsable(String token, String name) {
        // An unset variable reaches this point as the literal text of its placeholder.
        if (token == null || token.startsWith("${") || token.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "The " + name + " API token must be set and have at least " + MIN_LENGTH + " characters");
        }
    }

    /** Keeps the tokens out of logs and error reports. */
    @Override
    public String toString() {
        return "SecurityProperties[tokens redacted]";
    }
}
