package io.github.rnachoc.turno.core.tenant;

import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A calendar day at the clinic, which is not always 24 hours long and does not start at midnight UTC.
 *
 * @param date the local date
 * @param start the first instant of the day
 * @param end the first instant of the next day
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record ClinicDay(LocalDate date, Instant start, Instant end) {

    /** How far from today a day may be, which also keeps absurd dates away from the database. */
    private static final int MAX_YEARS_AWAY = 10;

    /**
     * Works out a day at the clinic.
     *
     * @param tenant the clinic
     * @param requested the date, or today if not given
     * @param now the current instant
     * @return the day
     * @throws DomainException if the date is too far away to mean anything
     */
    public static ClinicDay of(Tenant tenant, @Nullable LocalDate requested, Instant now) {
        LocalDate today = now.atZone(tenant.timezone()).toLocalDate();
        LocalDate date = requested != null ? requested : today;
        if (date.isBefore(today.minusYears(MAX_YEARS_AWAY)) || date.isAfter(today.plusYears(MAX_YEARS_AWAY))) {
            throw new DomainException(ErrorCode.DATE_RANGE_INVALID);
        }
        return new ClinicDay(
                date,
                date.atStartOfDay(tenant.timezone()).toInstant(),
                date.plusDays(1).atStartOfDay(tenant.timezone()).toInstant());
    }
}
