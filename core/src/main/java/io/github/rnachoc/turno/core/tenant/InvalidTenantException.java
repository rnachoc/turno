package io.github.rnachoc.turno.core.tenant;

import java.util.List;

/**
 * Signals that a tenant file cannot be used. The service refuses to start with one, because a wrong
 * rule would otherwise surface much later as a wrong booking.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public class InvalidTenantException extends RuntimeException {

    private final transient List<String> problems;

    /**
     * Creates the exception.
     *
     * @param source the file that was being read
     * @param problems every problem found, so that they can all be fixed at once
     */
    public InvalidTenantException(String source, List<String> problems) {
        super("Invalid tenant file " + source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the problems found.
     *
     * @return one description per problem
     */
    public List<String> problems() {
        return problems;
    }
}
