package io.github.rnachoc.turno.core.tenant;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration of the clinic served by this deployment, read from its tenant file.
 *
 * @param id unique identifier of the clinic
 * @param name display name
 * @param fictional must be true: Turno only runs on synthetic data
 * @param locale main locale, such as {@code es-ES}
 * @param languages languages the clinic attends patients in
 * @param timezone time zone in which the opening hours are expressed
 * @param currency ISO 4217 currency of every amount
 * @param contactEmail contact address, on the reserved {@code .example} domain
 * @param emergencyNumber number patients are referred to in an emergency
 * @param openingHours opening windows of each weekday
 * @param closures dates on which the clinic is closed regardless of its weekly hours
 * @param treatments treatments on offer
 * @param practitioners practitioners who work at the clinic
 * @param booking rules for making appointments
 * @param cancellation rules for cancelling appointments
 * @param outreach limits on contacting patients
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Tenant(
        String id,
        String name,
        boolean fictional,
        String locale,
        Set<String> languages,
        ZoneId timezone,
        String currency,
        String contactEmail,
        String emergencyNumber,
        Map<DayOfWeek, List<TimeWindow>> openingHours,
        Set<LocalDate> closures,
        List<Treatment> treatments,
        List<Practitioner> practitioners,
        BookingPolicy booking,
        CancellationPolicy cancellation,
        OutreachPolicy outreach) {

    /**
     * Finds a treatment by its identifier.
     *
     * @param treatmentId the identifier to look for
     * @return the treatment, if the clinic offers it
     */
    public Optional<Treatment> treatment(String treatmentId) {
        return treatments.stream().filter(t -> t.id().equals(treatmentId)).findFirst();
    }

    /**
     * Finds a practitioner by their identifier.
     *
     * @param practitionerId the identifier to look for
     * @return the practitioner, if they work at the clinic
     */
    public Optional<Practitioner> practitioner(String practitionerId) {
        return practitioners.stream().filter(p -> p.id().equals(practitionerId)).findFirst();
    }

    /**
     * A continuous period in which the clinic is open.
     *
     * @param open local opening time
     * @param close local closing time
     */
    public record TimeWindow(LocalTime open, LocalTime close) {}

    /**
     * A treatment on offer.
     *
     * @param id unique identifier
     * @param name display name per language
     * @param durationMinutes length of the appointment
     * @param priceCents price, in cents
     * @param depositCents deposit required to book, in cents
     */
    public record Treatment(
            String id,
            Map<String, String> name,
            int durationMinutes,
            int priceCents,
            int depositCents) {}

    /**
     * A practitioner of the clinic.
     *
     * @param id unique identifier
     * @param name display name
     * @param treatments identifiers of the treatments they perform
     * @param workingDays weekdays on which they work
     */
    public record Practitioner(
            String id, String name, Set<String> treatments, Set<DayOfWeek> workingDays) {}

    /**
     * Rules for making appointments.
     *
     * @param slotGranularityMinutes appointments start on multiples of this many minutes
     * @param minNoticeMinutes how far ahead an appointment must be booked
     * @param maxAdvanceDays how many days ahead an appointment can be booked
     * @param holdTtlMinutes how long a slot stays held before it is released
     * @param maxActiveHoldsPerPatient how many slots one patient can hold at the same time
     */
    public record BookingPolicy(
            int slotGranularityMinutes,
            int minNoticeMinutes,
            int maxAdvanceDays,
            int holdTtlMinutes,
            int maxActiveHoldsPerPatient) {}

    /**
     * Rules for cancelling appointments.
     *
     * @param freeUntilHoursBefore until how long before the start a cancellation is free
     * @param lateCancellationKeepsDeposit whether a late cancellation forfeits the deposit
     * @param noShowKeepsDeposit whether not showing up forfeits the deposit
     */
    public record CancellationPolicy(
            int freeUntilHoursBefore,
            boolean lateCancellationKeepsDeposit,
            boolean noShowKeepsDeposit) {}

    /**
     * Limits on contacting patients.
     *
     * @param allowedFrom local time from which patients may be contacted
     * @param allowedUntil local time until which patients may be contacted
     * @param maxAttemptsPerPatientPerDay maximum contact attempts per patient and day
     */
    public record OutreachPolicy(
            LocalTime allowedFrom, LocalTime allowedUntil, int maxAttemptsPerPatientPerDay) {}
}
