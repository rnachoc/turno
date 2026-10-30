package io.github.rnachoc.turno.core.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.turno.core.TestcontainersConfiguration;
import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/**
 * Acts as a compromised service would, using the role the service really connects with, and tries to
 * do what that role must never be able to. The rest of the integration tests run as this same role,
 * which proves that it can do everything it needs.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class DatabasePrivilegesIT extends ApiIntegrationTest {

    @Test
    void theServiceReallyConnectsAsTheRestrictedRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(TestcontainersConfiguration.APP_ROLE);
    }

    @Test
    void theRestrictedRoleHasNoSpecialAttributes() {
        Boolean privileged =
                jdbc.sql(
                                """
                                SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication
                                FROM pg_roles WHERE rolname = current_user
                                """)
                        .query(Boolean.class)
                        .single();

        assertThat(privileged).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE audit_event SET actor = 'nobody'",
                "DELETE FROM audit_event",
                "TRUNCATE audit_event",
                "ALTER TABLE audit_event DISABLE TRIGGER USER",
                "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change",
                "DROP TRIGGER audit_event_no_change ON audit_event",
                "DROP TABLE audit_event",
                "DROP FUNCTION reject_audit_change() CASCADE"
            })
    void theAuditTrailIsOutOfReach(String attack) {
        newPatient();
        int before = count("SELECT count(*) FROM audit_event");

        assertThatThrownBy(() -> jdbc.sql(attack).update())
                .isInstanceOf(DataAccessException.class)
                // Refused for lack of privileges, before the trigger even has a say.
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|must be owner).*");

        assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM patient",
                "DELETE FROM appointment",
                "TRUNCATE patient CASCADE",
                "TRUNCATE appointment",
                "TRUNCATE idempotency_key",
                "DROP TABLE appointment",
                "ALTER TABLE appointment DROP CONSTRAINT appointment_no_practitioner_overlap",
                "ALTER TABLE appointment DISABLE TRIGGER ALL",
                "CREATE TABLE stolen (data text)",
                "DELETE FROM flyway_schema_history",
                "CREATE ROLE intruder LOGIN SUPERUSER",
                "ALTER ROLE turno_app SUPERUSER",
                "SET ROLE test",
                "COPY patient TO PROGRAM 'id'"
            })
    void nothingStructuralOrDestructiveIsAllowed(String attack) {
        // Checking the reason matters: running these the wrong way fails with or without privileges.
        assertThatThrownBy(() -> jdbc.sql(attack).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|must be owner).*");
    }

    @Test
    void theMigrationHistoryCannotBeRead() {
        assertThatThrownBy(
                        () -> jdbc.sql("SELECT count(*) FROM flyway_schema_history")
                                .query(Integer.class)
                                .single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void anAuditEntryCanStillBeWritten() {
        // Otherwise no operation of the service could succeed.
        newPatient();

        assertThat(count("SELECT count(*) FROM audit_event")).isPositive();
    }

    @Test
    void evenTheOwnerIsStoppedByTheTrigger() {
        newPatient();

        assertThatThrownBy(() -> owner.jdbc().sql("UPDATE audit_event SET actor = 'nobody'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.jdbc().sql("DELETE FROM audit_event").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.jdbc().sql("TRUNCATE audit_event").update())
                .hasMessageContaining("append-only");
    }
}
