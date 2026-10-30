package io.github.rnachoc.turno.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks that a good tenant file loads faithfully and that a bad one stops the service.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class TenantLoaderTest {

    private static final Path FIXTURE = Path.of("src/test/resources/tenants/test-clinic.yaml");

    @TempDir
    private Path dir;

    @Test
    void loadsTheDemoClinicThatShipsWithTheRepository() {
        Tenant tenant = TenantLoader.load(Path.of("../config/tenants/mirador-dental.yaml"));

        assertThat(tenant.id()).isEqualTo("mirador-dental");
        assertThat(tenant.fictional()).isTrue();
        assertThat(tenant.treatments()).isNotEmpty();
    }

    @Test
    void readsEverySectionIncludingYamlAliases() {
        Tenant tenant = TenantLoader.load(FIXTURE);

        assertThat(tenant.timezone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThat(tenant.openingHours().get(DayOfWeek.FRIDAY))
                .as("friday is written as an alias of monday")
                .containsExactly(
                        new Tenant.TimeWindow(LocalTime.of(9, 0), LocalTime.of(14, 0)),
                        new Tenant.TimeWindow(LocalTime.of(16, 0), LocalTime.of(20, 0)));
        assertThat(tenant.openingHours().get(DayOfWeek.SUNDAY)).isEmpty();
        assertThat(tenant.treatment("filling").orElseThrow().depositCents()).isEqualTo(1500);
        assertThat(tenant.practitioner("ben").orElseThrow().workingDays())
                .containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(tenant.booking().maxActiveHoldsPerPatient()).isEqualTo(1);
        assertThat(tenant.cancellation().freeUntilHoursBefore()).isEqualTo(24);
        assertThat(tenant.outreach().allowedUntil()).isEqualTo(LocalTime.of(20, 0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "real clinic          | 'fictional: true'              | 'fictional: false'           | fictional must be true",
                "real mailbox         | hello@test-clinic.example      | hello@test-clinic.com        | contact_email",
                "unknown currency     | 'currency: EUR'                | 'currency: ZZZ'              | unknown currency",
                "unknown zone         | Europe/Madrid                  | Europe/Atlantis              | Atlantis",
                "unknown section      | 'locale: es-ES'                | 'locale: es-ES\nsurprise: 1' | surprise",
                "missing section      | 'currency: EUR'                | ''                           | currency",
                "inverted window      | 'open: \"09:00\", close: \"14:00\"' | 'open: \"14:00\", close: \"09:00\"' | must open before it closes",
                "overlapping windows  | 'open: \"16:00\"'              | 'open: \"13:00\"'            | must not overlap",
                "deposit above price  | 'deposit_cents: 1500'          | 'deposit_cents: 9000'        | deposit must be between",
                "negative price       | 'price_cents: 3500'            | 'price_cents: -1'            | price must not be negative",
                "zero duration        | 'duration_minutes: 30'         | 'duration_minutes: 0'        | duration must be positive",
                "unknown treatment    | '[check-up, filling]'          | '[check-up, laser]'          | unknown treatment laser",
                "orphan treatment     | '[check-up, filling]'          | '[check-up]'                 | no practitioner performs it",
                "missing translation  | 'en: Filling'                  | 'it: Filling'                | needs a name in every language",
                "works when closed    | 'working_days: [monday, wednesday]' | 'working_days: [sunday]' | when it is closed",
                "duplicate treatment  | 'id: filling'                  | 'id: check-up'               | duplicate treatment",
                "duplicate practitioner | 'id: ben'                    | 'id: ana'                    | duplicate practitioner",
                "zero granularity     | 'slot_granularity_minutes: 15' | 'slot_granularity_minutes: 0' | granularity",
                "zero hold            | 'hold_ttl_minutes: 5'          | 'hold_ttl_minutes: 0'        | hold_ttl_minutes must be positive",
                "hold beyond notice   | 'hold_ttl_minutes: 5'          | 'hold_ttl_minutes: 500'      | must not exceed min_notice",
                "zero active holds    | 'max_active_holds_per_patient: 1' | 'max_active_holds_per_patient: 0' | max_active_holds",
                "inverted outreach    | 'allowed_from: \"09:00\"'      | 'allowed_from: \"21:00\"'    | allowed_from must be before",
            })
    void rejectsInvalidFiles(String name, String from, String to, String expectedProblem)
            throws IOException {
        String original = Files.readString(FIXTURE);
        assertThat(original).as("the fixture must contain the text to replace").contains(from);
        Path file = dir.resolve("tenant.yaml");
        Files.writeString(file, original.replace(from, to.replace("\\n", "\n")));

        assertThatThrownBy(() -> TenantLoader.load(file))
                .isInstanceOf(InvalidTenantException.class)
                .hasMessageContaining(expectedProblem);
    }

    @Test
    void reportsEveryProblemAtOnce() throws IOException {
        String original = Files.readString(FIXTURE);
        Path file = dir.resolve("tenant.yaml");
        Files.writeString(
                file,
                original.replace("fictional: true", "fictional: false")
                        .replace("hold_ttl_minutes: 5", "hold_ttl_minutes: 500"));

        assertThatThrownBy(() -> TenantLoader.load(file))
                .isInstanceOfSatisfying(
                        InvalidTenantException.class,
                        e -> assertThat(e.problems()).hasSize(2));
    }

    @Test
    void rejectsAMissingFile() {
        assertThatThrownBy(() -> TenantLoader.load(dir.resolve("nope.yaml")))
                .isInstanceOf(InvalidTenantException.class);
    }

    @Test
    void rejectsMalformedYaml() throws IOException {
        Path file = dir.resolve("tenant.yaml");
        Files.writeString(file, "id: [unclosed");

        assertThatThrownBy(() -> TenantLoader.load(file))
                .isInstanceOf(InvalidTenantException.class);
    }

    @Test
    void refusesToInstantiateArbitraryClassesFromYaml() throws IOException {
        Path file = dir.resolve("tenant.yaml");
        Files.writeString(file, "id: !!java.net.URL [\"http://attacker.example/\"]");

        assertThatThrownBy(() -> TenantLoader.load(file))
                .isInstanceOf(InvalidTenantException.class);
    }
}
