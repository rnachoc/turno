package io.github.rnachoc.turno.core.activity;

import java.util.List;
import java.util.UUID;

/**
 * A conversation with everything that happened in it.
 *
 * @param id unique identifier
 * @param patientId the patient
 * @param patientName how to address them
 * @param steps what happened, in order
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record ConversationDetail(UUID id, UUID patientId, String patientName, List<AgentStep> steps) {}
