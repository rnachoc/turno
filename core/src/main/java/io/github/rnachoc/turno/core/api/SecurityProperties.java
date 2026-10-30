package io.github.rnachoc.turno.core.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API tokens. The service refuses to start with a missing, short or shared token, and the messages
 * never include their values.
 *
 * @param agentsToken token of the agents service
 * @param staffToken token of the staff console
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@ConfigurationProperties("turno.security")
public record SecurityProperties(String agentsToken, String staffToken) {

    private static final int MIN_LENGTH = 32;

    /** Validates both tokens. */
    public SecurityProperties {
        requireUsable(agentsToken, "agents");
        requireUsable(staffToken, "staff");
        if (agentsToken.equals(staffToken)) {
            throw new IllegalArgumentException("The agents and staff tokens must be different");
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
