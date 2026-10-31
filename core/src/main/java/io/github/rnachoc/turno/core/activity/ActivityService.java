package io.github.rnachoc.turno.core.activity;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.metrics.DomainMetrics;
import io.github.rnachoc.turno.core.patient.PatientRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a record of what the agents did, and of the conversations they passed to a person.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class ActivityService {

    private final ActivityRepository activity;
    private final PatientRepository patients;
    private final AuditService audit;
    private final DomainMetrics metrics;
    private final Clock clock;

    ActivityService(
            ActivityRepository activity,
            PatientRepository patients,
            AuditService audit,
            DomainMetrics metrics,
            Clock clock) {
        this.activity = activity;
        this.patients = patients;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Stores steps of a conversation. Steps the core already has are left as they were, so a retry
     * changes nothing and cannot reopen a handover that someone has resolved.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param conversationId the conversation
     * @param steps the steps; their timestamps are ignored in favour of the core's own clock
     * @return how many were new
     */
    @Transactional
    public int record(ApiPrincipal principal, UUID patientId, UUID conversationId, List<AgentStep> steps) {
        if (!patients.lock(patientId)) {
            throw new DomainException(ErrorCode.PATIENT_NOT_FOUND);
        }
        Instant now = clock.instant();
        activity.insertConversationIfNew(conversationId, patientId, now);
        if (!activity.patientOf(conversationId).orElseThrow().equals(patientId)) {
            throw new DomainException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        int added = 0;
        for (AgentStep step : steps) {
            AgentStep stamped =
                    new AgentStep(
                            step.position(),
                            step.kind(),
                            step.detail(),
                            step.inputTokens(),
                            step.outputTokens(),
                            step.estimatedCostUsd(),
                            step.seconds(),
                            now);
            if (!activity.insertStepIfNew(conversationId, stamped)) {
                continue;
            }
            added++;
            metrics.agentStep(
                    step.kind(), step.inputTokens(), step.outputTokens(), step.estimatedCostUsd(), step.seconds());
            if (AgentStep.HANDOVER.equals(step.kind())) {
                openHandover(principal, conversationId, patientId, step.detail(), now);
            }
        }
        audit.accepted(
                principal.actor(), "conversation.steps", conversationId.toString(), patientId, Map.of("added", added));
        return added;
    }

    private void openHandover(ApiPrincipal principal, UUID conversationId, UUID patientId, String reason, Instant now) {
        UUID id = UUID.randomUUID();
        if (activity.openHandoverIfNotOpen(id, conversationId, patientId, reason, now)) {
            audit.accepted(principal.actor(), "handover.open", id.toString(), patientId, Map.of("reason", reason));
            metrics.handoverOpened(reason);
        }
    }

    /**
     * Lists the conversations that wait for a person.
     *
     * @param principal the caller
     * @return emergencies first, then whoever has waited longest
     */
    @Transactional
    public List<Handover> openHandovers(ApiPrincipal principal) {
        audit.accepted(principal.actor(), "handover.list", null, null, Map.of());
        return activity.openHandovers();
    }

    /**
     * Marks a handover as dealt with.
     *
     * @param principal the caller
     * @param id the handover
     */
    @Transactional
    public void resolve(ApiPrincipal principal, UUID id) {
        UUID patientId =
                activity.resolve(id, principal.actor(), clock.instant())
                        .orElseThrow(() -> new DomainException(ErrorCode.HANDOVER_NOT_FOUND));
        audit.accepted(principal.actor(), "handover.resolve", id.toString(), patientId, Map.of());
    }

    /**
     * Lists the latest conversations.
     *
     * @param principal the caller
     * @return the conversations, newest first
     */
    @Transactional
    public List<ConversationSummary> conversations(ApiPrincipal principal) {
        audit.accepted(principal.actor(), "conversation.list", null, null, Map.of());
        return activity.recentConversations();
    }

    /**
     * Reads what happened in a conversation.
     *
     * @param principal the caller
     * @param conversationId the conversation
     * @return the conversation and its steps, in order
     */
    @Transactional
    public ConversationDetail conversation(ApiPrincipal principal, UUID conversationId) {
        ConversationDetail conversation =
                activity.conversation(conversationId)
                        .orElseThrow(() -> new DomainException(ErrorCode.CONVERSATION_NOT_FOUND));
        audit.accepted(
                principal.actor(), "conversation.read", conversationId.toString(), conversation.patientId(), Map.of());
        return conversation;
    }
}
