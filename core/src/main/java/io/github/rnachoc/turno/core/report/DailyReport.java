package io.github.rnachoc.turno.core.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The owner's report of one day, worked out from the records each time it is asked for. No model is
 * involved, so every figure can be traced to rows in the database.
 *
 * @param date the day, at the clinic
 * @param generatedAt when it was worked out, since later events can still change a past day
 * @param timezone the clinic's time zone
 * @param currency the currency of every amount
 * @param agenda the appointments of the day
 * @param money what the appointments of the day were worth; no payment exists yet
 * @param occupancy how much of the working time was booked
 * @param activity what was done during the day, whatever day the appointments were for
 * @param waitlist how freed slots were refilled
 * @param agents what the agents did during the day
 * @param attention what is waiting for someone right now, whichever day the report is about
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record DailyReport(
        LocalDate date,
        Instant generatedAt,
        String timezone,
        String currency,
        Agenda agenda,
        Money money,
        Occupancy occupancy,
        Activity activity,
        Waitlist waitlist,
        Agents agents,
        Attention attention) {

    /**
     * Appointments of the day by status. Holds that have run out are not counted.
     *
     * @param held waiting for a confirmation
     * @param confirmed confirmed, with no outcome yet
     * @param completed attended
     * @param noShow not attended
     * @param cancelled cancelled
     */
    public record Agenda(int held, int confirmed, int completed, int noShow, int cancelled) {}

    /**
     * Amounts in cents.
     *
     * @param completedCents price of the attended appointments
     * @param noShowCents price of the appointments not attended
     * @param forfeitedDepositCents deposits recorded as forfeited, though nothing is charged yet
     */
    public record Money(long completedCents, long noShowCents, long forfeitedDepositCents) {}

    /**
     * Working time against booked time.
     *
     * @param openMinutes working time of every practitioner who works that day
     * @param bookedMinutes time of the confirmed, attended and not attended appointments
     * @param percent booked time over working time, or nothing if the clinic was closed
     */
    public record Occupancy(int openMinutes, int bookedMinutes, @Nullable Integer percent) {}

    /**
     * Operations of the day, from the audit trail.
     *
     * @param booked appointments confirmed
     * @param bookedByAgents of those, how many the agents confirmed
     * @param cancelled appointments cancelled
     * @param rescheduled appointments moved
     * @param refused operations the core refused, for any caller
     */
    public record Activity(int booked, int bookedByAgents, int cancelled, int rescheduled, int refused) {}

    /**
     * The refill of freed slots.
     *
     * @param slotsFreed slots freed during the day
     * @param offersMade offers made during the day
     * @param offersAccepted of those, how many are accepted so far
     * @param recoveredCents price of the accepted offers
     * @param waitingNow patients on the list right now
     */
    public record Waitlist(int slotsFreed, int offersMade, int offersAccepted, long recoveredCents, int waitingNow) {}

    /**
     * The agents' work during the day.
     *
     * @param conversations conversations with any step that day
     * @param modelCalls calls to the model
     * @param inputTokens tokens read
     * @param outputTokens tokens written
     * @param estimatedCostUsd what the calls would cost on the API; no money is actually spent
     * @param estimatedCostPerBookingUsd that cost over the agents' bookings, if they made any
     * @param blockedReplies replies the guards stopped
     * @param handovers conversations passed to a person
     * @param handoversByReason the same, by the reason of each
     */
    public record Agents(
            int conversations,
            int modelCalls,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostUsd,
            @Nullable BigDecimal estimatedCostPerBookingUsd,
            int blockedReplies,
            int handovers,
            Map<String, Integer> handoversByReason) {}

    /**
     * What waits for someone.
     *
     * @param openHandovers conversations waiting for a person
     * @param awaitingOutcome appointments that have started and need an attended or no-show decision
     */
    public record Attention(int openHandovers, int awaitingOutcome) {}
}
