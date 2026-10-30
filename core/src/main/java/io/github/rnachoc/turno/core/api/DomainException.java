package io.github.rnachoc.turno.core.api;

/**
 * A request refused by a business rule. Throwing it rolls back the current transaction.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    /**
     * Creates the exception.
     *
     * @param code the reason for the refusal
     */
    public DomainException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    /**
     * Returns the reason for the refusal.
     *
     * @return the error code
     */
    public ErrorCode code() {
        return code;
    }
}
