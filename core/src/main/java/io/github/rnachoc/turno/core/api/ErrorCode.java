package io.github.rnachoc.turno.core.api;

import org.springframework.http.HttpStatus;

/**
 * Every reason the API can refuse a request. Agents act on these stable codes, never on free text.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is not valid."),
    DATE_RANGE_INVALID(HttpStatus.BAD_REQUEST, "The date range is not valid."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "This operation needs an Idempotency-Key header."),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "The Idempotency-Key header is not valid."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid API token is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "This token cannot perform this operation."),
    PATIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The patient does not exist."),
    APPOINTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The appointment does not exist."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The resource does not exist."),
    WAITLIST_ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "The waitlist entry does not exist or is no longer active."),
    FREED_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "The freed slot does not exist."),
    OFFER_NOT_FOUND(HttpStatus.NOT_FOUND, "The offer does not exist."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "The conversation does not exist."),
    HANDOVER_NOT_FOUND(HttpStatus.NOT_FOUND, "The handover does not exist or has already been resolved."),
    ALREADY_ON_WAITLIST(HttpStatus.CONFLICT, "The patient is already waiting for this treatment."),
    PATIENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "A patient with this phone number already exists."),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "The practitioner is no longer free at that time."),
    PATIENT_TIME_CONFLICT(HttpStatus.CONFLICT, "The patient already has an appointment at that time."),
    HOLD_LIMIT_REACHED(HttpStatus.CONFLICT, "The patient already holds as many slots as allowed."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "The hold has expired."),
    INVALID_STATE(HttpStatus.CONFLICT, "The appointment does not allow this operation in its current state."),
    RESCHEDULE_WINDOW_CLOSED(HttpStatus.CONFLICT, "It is too late to reschedule this appointment."),
    APPOINTMENT_ALREADY_STARTED(HttpStatus.CONFLICT, "The appointment has already started."),
    APPOINTMENT_NOT_STARTED(HttpStatus.CONFLICT, "The appointment has not started yet."),
    CONCURRENT_CONFLICT(HttpStatus.CONFLICT, "Another operation got in the way. Retry the request."),
    TREATMENT_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "The clinic does not offer this treatment."),
    PRACTITIONER_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "The practitioner does not work at the clinic."),
    PRACTITIONER_CANNOT_PERFORM(HttpStatus.UNPROCESSABLE_CONTENT, "The practitioner does not perform this treatment."),
    SLOT_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_CONTENT, "The clinic's rules do not allow an appointment at that time."),
    LANGUAGE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "The clinic does not attend patients in this language."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "This Idempotency-Key was already used for a different request."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Wait before trying again."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * Returns the HTTP status of this error.
     *
     * @return the status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the fixed description of this error, which never includes any request data.
     *
     * @return the description
     */
    public String message() {
        return message;
    }
}
