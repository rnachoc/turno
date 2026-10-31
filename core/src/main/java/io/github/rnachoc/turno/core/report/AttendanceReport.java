package io.github.rnachoc.turno.core.report;

import java.time.LocalDate;
import java.util.List;

/**
 * How appointments ended over a period, by treatment and practitioner. It holds no patient's data.
 *
 * @param from the first day, at the clinic
 * @param to the last day, at the clinic
 * @param currency the currency of every amount
 * @param rows the figures, with the most missed first
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record AttendanceReport(LocalDate from, LocalDate to, String currency, List<Row> rows) {

    /**
     * The figures of a treatment with a practitioner.
     *
     * @param treatmentId the treatment
     * @param practitionerId the practitioner
     * @param attended appointments attended
     * @param noShow appointments not attended
     * @param cancelled appointments cancelled
     * @param noShowCents price of the appointments not attended
     */
    public record Row(
            String treatmentId, String practitionerId, int attended, int noShow, int cancelled, long noShowCents) {}
}
