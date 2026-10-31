package io.github.rnachoc.turno.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.turno.core.api.DomainException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Days at the clinic, whose length depends on the clocks.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class ClinicDayTest {

    private static final Tenant TENANT = TenantLoader.load(Path.of("src/test/resources/tenants/test-clinic.yaml"));
    private static final Instant NOW = Instant.parse("2027-03-01T07:00:00Z");

    @ParameterizedTest
    @CsvSource({"2027-03-02,24", "2027-03-28,23", "2027-10-31,25"})
    void aDayLastsWhatTheClocksSay(LocalDate date, long hours) {
        ClinicDay day = ClinicDay.of(TENANT, date, NOW);

        assertThat(Duration.between(day.start(), day.end())).isEqualTo(Duration.ofHours(hours));
    }

    @Test
    void itStartsAtMidnightAtTheClinic() {
        ClinicDay day = ClinicDay.of(TENANT, LocalDate.parse("2027-03-02"), NOW);

        assertThat(day.start()).isEqualTo(Instant.parse("2027-03-01T23:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2027-03-02T23:00:00Z"));
    }

    @Test
    void withoutADateItIsTodayAtTheClinic() {
        assertThat(ClinicDay.of(TENANT, null, Instant.parse("2027-03-01T23:30:00Z")).date())
                .isEqualTo(LocalDate.parse("2027-03-02"));
    }

    @Test
    void theLimitsAreTenYearsEitherWay() {
        assertThat(ClinicDay.of(TENANT, LocalDate.parse("2037-03-01"), NOW).date()).isEqualTo("2037-03-01");
        assertThat(ClinicDay.of(TENANT, LocalDate.parse("2017-03-01"), NOW).date()).isEqualTo("2017-03-01");
        assertThatThrownBy(() -> ClinicDay.of(TENANT, LocalDate.parse("2037-03-02"), NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ClinicDay.of(TENANT, LocalDate.parse("2017-02-28"), NOW))
                .isInstanceOf(DomainException.class);
    }
}
