package io.github.rnachoc.turno.core.waitlist;

import java.time.Instant;
import java.util.UUID;

/**
 * A period that became free because an appointment was cancelled or moved.
 *
 * @param id unique identifier
 * @param practitionerId the practitioner who is now free
 * @param startsAt first instant of the period
 * @param endsAt instant at which the period ends, exclusive
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record FreedSlot(UUID id, String practitionerId, Instant startsAt, Instant endsAt) {}
