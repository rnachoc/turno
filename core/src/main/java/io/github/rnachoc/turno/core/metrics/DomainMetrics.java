package io.github.rnachoc.turno.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts what happens in the clinic, for the dashboards. Anything counted inside a transaction only
 * counts once it commits, so a rollback leaves no trace here either.
 *
 * <p>Labels only hold values from the lists below or from the code itself. What an agent sends could
 * otherwise create a new series per value, and it must never hold anything about a patient.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
public class DomainMetrics {

    private static final String AUDIT_EVENTS = "turno.audit.events";
    private static final String REFUSALS = "turno.refusals";
    private static final String STEPS = "turno.agent.steps";
    private static final String TOKENS = "turno.agent.tokens";
    private static final String COST = "turno.agent.estimated.cost.usd";
    private static final String HANDOVERS = "turno.handovers";
    private static final String OTHER = "other";
    private static final Set<String> STEP_KINDS =
            Set.of("model", "consent", "action", "refused", "blocked", "rejected", "handover");
    private static final Set<String> HANDOVER_REASONS =
            Set.of("triage_emergency", "triage_medical", "core_down", "model_failure", "invalid_format", "too_many_steps");

    private final MeterRegistry registry;

    DomainMetrics(MeterRegistry registry) {
        this.registry = registry;
        startAtZero();
    }

    /**
     * Creates the main series before anything happens. Prometheus reads a counter's growth from its
     * first sample, so the event that created a series would otherwise never count, and at the pace
     * of a small clinic that is most of them.
     */
    private void startAtZero() {
        for (String actor : List.of("agents", "staff")) {
            for (String action : List.of("appointment.confirm", "appointment.cancel")) {
                registry.counter(AUDIT_EVENTS, "actor", actor, "action", action, "outcome", "ACCEPTED");
            }
            registry.counter(REFUSALS, "actor", actor);
        }
        registry.counter(AUDIT_EVENTS, "actor", "workflows", "action", "waitlist.offer", "outcome", "ACCEPTED");
        STEP_KINDS.forEach(kind -> registry.counter(STEPS, "kind", kind));
        registry.counter(STEPS, "kind", OTHER);
        HANDOVER_REASONS.forEach(reason -> registry.counter(HANDOVERS, "reason", reason));
        registry.counter(HANDOVERS, "reason", OTHER);
        registry.counter(TOKENS, "direction", "input");
        registry.counter(TOKENS, "direction", "output");
        registry.counter(COST);
        modelDuration();
    }

    private Timer modelDuration() {
        return Timer.builder("turno.agent.model.duration").publishPercentileHistogram().register(registry);
    }

    /**
     * Counts an entry of the audit trail, which is where every operation of the clinic ends up.
     *
     * @param actor who did it
     * @param action what they did
     * @param outcome whether it was accepted
     */
    public void audited(String actor, String action, String outcome) {
        afterCommit(
                () -> {
                    registry.counter(AUDIT_EVENTS, "actor", actor, "action", action, "outcome", outcome).increment();
                    if ("REJECTED".equals(outcome)) {
                        registry.counter(REFUSALS, "actor", actor).increment();
                    }
                });
    }

    /**
     * Counts a step that the agents reported for the first time.
     *
     * @param kind what happened
     * @param inputTokens tokens read by the model
     * @param outputTokens tokens written by the model
     * @param estimatedCostUsd what the call would cost on the API
     * @param seconds how long the call took
     */
    public void agentStep(String kind, int inputTokens, int outputTokens, BigDecimal estimatedCostUsd, BigDecimal seconds) {
        afterCommit(
                () -> {
                    String known = STEP_KINDS.contains(kind) ? kind : OTHER;
                    registry.counter(STEPS, "kind", known).increment();
                    registry.counter(TOKENS, "direction", "input").increment(inputTokens);
                    registry.counter(TOKENS, "direction", "output").increment(outputTokens);
                    registry.counter(COST).increment(estimatedCostUsd.doubleValue());
                    if ("model".equals(kind)) {
                        modelDuration().record(Duration.ofMillis(seconds.movePointRight(3).longValue()));
                    }
                });
    }

    /**
     * Counts a conversation passed to a person.
     *
     * @param reason why
     */
    public void handoverOpened(String reason) {
        afterCommit(
                () ->
                        registry.counter(HANDOVERS, "reason", HANDOVER_REASONS.contains(reason) ? reason : OTHER)
                                .increment());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}
