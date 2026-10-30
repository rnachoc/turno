package io.github.rnachoc.turno.core.availability;

import java.time.Instant;

/**
 * A period in which a practitioner could attend a treatment.
 *
 * @param practitionerId the practitioner
 * @param treatmentId the treatment
 * @param startsAt first instant of the appointment
 * @param endsAt instant at which the appointment ends, exclusive
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Slot(String practitionerId, String treatmentId, Instant startsAt, Instant endsAt) {}
