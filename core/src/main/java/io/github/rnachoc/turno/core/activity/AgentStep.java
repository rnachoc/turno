package io.github.rnachoc.turno.core.activity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One thing that happened in a conversation. It holds a code rather than words, so that what a
 * patient writes never reaches this table.
 *
 * @param position its place in the conversation, from zero
 * @param kind what happened
 * @param detail a stable code or the name of a model
 * @param inputTokens tokens read by the model, if it was called
 * @param outputTokens tokens written by the model, if it was called
 * @param estimatedCostUsd what the call would cost on the API; no money is actually spent
 * @param seconds how long the call took
 * @param createdAt when the core heard about it
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record AgentStep(
        int position,
        String kind,
        String detail,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCostUsd,
        BigDecimal seconds,
        Instant createdAt) {

    /** The kind of step that asks for a person. */
    static final String HANDOVER = "handover";
}
