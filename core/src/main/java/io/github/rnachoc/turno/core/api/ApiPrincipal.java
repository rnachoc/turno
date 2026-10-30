package io.github.rnachoc.turno.core.api;

/**
 * Who is calling the API, derived from the token and never from anything the caller can claim.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public enum ApiPrincipal {
    /** The AI agents: they act on behalf of patients and read untrusted text. */
    AGENTS("agents"),
    /** The clinic's staff, through the console. */
    STAFF("staff");

    /** Request attribute under which the authenticated principal is stored. */
    public static final String ATTRIBUTE = "turno.principal";

    private final String actor;

    ApiPrincipal(String actor) {
        this.actor = actor;
    }

    /**
     * Returns the name recorded in the audit trail.
     *
     * @return the actor name
     */
    public String actor() {
        return actor;
    }
}
