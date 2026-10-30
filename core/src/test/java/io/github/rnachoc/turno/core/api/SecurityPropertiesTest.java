package io.github.rnachoc.turno.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Checks that the service refuses to start with weak tokens, and that it never prints them.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class SecurityPropertiesTest {

    private static final String GOOD = "a-sufficiently-long-token-for-agents";
    private static final String OTHER = "a-sufficiently-long-token-for-staff!";

    @Test
    void acceptsTwoLongDifferentTokens() {
        SecurityProperties properties = new SecurityProperties(GOOD, OTHER);

        assertThat(properties.agentsToken()).isEqualTo(GOOD);
        assertThat(properties.staffToken()).isEqualTo(OTHER);
    }

    @Test
    void refusesAMissingToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityProperties(null, OTHER));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityProperties(GOOD, null));
    }

    @Test
    void refusesAShortTokenAndDoesNotRepeatIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityProperties("short-secret", OTHER))
                .withMessageNotContaining("short-secret");
    }

    @Test
    void refusesAnUnresolvedPlaceholderHoweverLongItIs() {
        String placeholder = "${TURNO_API_TOKEN_AGENTS_WITH_A_VERY_LONG_NAME}";

        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityProperties(placeholder, OTHER));
    }

    @Test
    void refusesTheSameTokenForBothRolesAndDoesNotRepeatIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityProperties(GOOD, GOOD))
                .withMessageNotContaining(GOOD);
    }

    @Test
    void neverPrintsTheTokens() {
        assertThat(new SecurityProperties(GOOD, OTHER).toString()).doesNotContain(GOOD).doesNotContain(OTHER);
    }
}
