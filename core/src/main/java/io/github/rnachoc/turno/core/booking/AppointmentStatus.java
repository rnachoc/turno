package io.github.rnachoc.turno.core.booking;

/**
 * Life cycle of an appointment. Only {@link #HOLD} and {@link #CONFIRMED} occupy time.
 *
 * <pre>
 * HOLD ──► CONFIRMED ──► COMPLETED | NO_SHOW | CANCELLED
 *   └────► RELEASED | EXPIRED
 * </pre>
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public enum AppointmentStatus {
    /** The slot is reserved for a few minutes while the patient decides. */
    HOLD,
    /** The appointment is booked. */
    CONFIRMED,
    /** The patient gave up the hold. */
    RELEASED,
    /** The hold ran out of time. */
    EXPIRED,
    /** The appointment was cancelled before it started. */
    CANCELLED,
    /** The patient attended. */
    COMPLETED,
    /** The patient did not attend. */
    NO_SHOW
}
