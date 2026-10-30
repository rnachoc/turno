package io.github.rnachoc.turno.core.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/**
 * Attacks the database directly, skipping all the application code. A bug in the services must not
 * be able to create a double booking or rewrite the audit trail.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class DatabaseConstraintsIT extends ApiIntegrationTest {

    private UUID patient;
    private UUID otherPatient;

    @BeforeEach
    void createPatients() {
        patient = newPatient();
        otherPatient = newPatient();
    }

    @Test
    void refusesAnOverlapForThePractitioner() {
        insert(patient, "ana", "09:00", "09:30", "CONFIRMED");

        assertThatThrownBy(() -> insert(otherPatient, "ana", "09:15", "09:45", "CONFIRMED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("appointment_no_practitioner_overlap");
    }

    @Test
    void refusesAnOverlapForThePatient() {
        insert(patient, "ana", "09:00", "09:30", "HOLD");

        assertThatThrownBy(() -> insert(patient, "ben", "09:15", "09:45", "CONFIRMED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("appointment_no_patient_overlap");
    }

    @Test
    void aHoldBlocksAsMuchAsABooking() {
        insert(patient, "ana", "09:00", "09:30", "HOLD");

        assertThatThrownBy(() -> insert(otherPatient, "ana", "09:00", "09:30", "HOLD"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void touchingRangesDoNotOverlap() {
        insert(patient, "ana", "09:00", "09:30", "CONFIRMED");

        insert(otherPatient, "ana", "09:30", "10:00", "CONFIRMED");

        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASED", "EXPIRED", "CANCELLED", "COMPLETED", "NO_SHOW"})
    void finishedAppointmentsDoNotOccupyTime(String status) {
        insert(patient, "ana", "09:00", "09:30", status);

        insert(otherPatient, "ana", "09:00", "09:30", "CONFIRMED");

        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(2);
    }

    @Test
    void anUpdateCannotCreateAnOverlapEither() {
        insert(patient, "ana", "09:00", "09:30", "CONFIRMED");
        insert(otherPatient, "ana", "10:00", "10:30", "CONFIRMED");

        assertThatThrownBy(
                        () -> jdbc.sql("UPDATE appointment SET starts_at = '2027-03-09 09:15+00', ends_at = '2027-03-09 09:45+00' WHERE patient_id = '" + otherPatient + "'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reactivatingACancelledAppointmentOverAnotherIsRefused() {
        insert(patient, "ana", "09:00", "09:30", "CANCELLED");
        insert(otherPatient, "ana", "09:00", "09:30", "CONFIRMED");

        assertThatThrownBy(
                        () -> jdbc.sql("UPDATE appointment SET status = 'CONFIRMED' WHERE patient_id = '" + patient + "'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refusesRowsThatMakeNoSense() {
        assertThatThrownBy(() -> insert(patient, "ana", "09:30", "09:00", "CONFIRMED"))
                .as("ends before it starts")
                .hasMessageContaining("appointment_valid_range");
        assertThatThrownBy(() -> insert(patient, "ana", "09:00", "09:00", "CONFIRMED"))
                .as("empty range")
                .hasMessageContaining("appointment_valid_range");
        assertThatThrownBy(() -> insert(patient, "ana", "09:00", "09:30", "INVENTED"))
                .hasMessageContaining("appointment_valid_status");
        assertThatThrownBy(() -> insertWithMoney(100, 200))
                .as("deposit above the price")
                .hasMessageContaining("appointment_valid_money");
        assertThatThrownBy(() -> insertWithMoney(-1, 0)).hasMessageContaining("appointment_valid_money");
        assertThatThrownBy(() -> insertWithExpiry("HOLD", null)).hasMessageContaining("appointment_hold_has_expiry");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "ana", "09:00", "09:30", "CONFIRMED"))
                .as("unknown patient")
                .isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM appointment")).isZero();
    }

    @Test
    void twoPatientsCannotShareAPhoneNumber() {
        String phone = jdbc.sql("SELECT phone FROM patient WHERE id = :id").param("id", patient).query(String.class).single();

        assertThatThrownBy(
                        () -> jdbc.sql("UPDATE patient SET phone = :phone WHERE id = :id")
                                .param("phone", phone)
                                .param("id", otherPatient)
                                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theAuditTrailCannotBeRewritten() {
        assertThat(count("SELECT count(*) FROM audit_event")).as("registering patients left entries").isPositive();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET actor = 'nobody'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_event").update())
                .hasMessageContaining("append-only");
    }

    private void insert(UUID patientId, String practitioner, String from, String to, String status) {
        jdbc.sql(
                        """
                        INSERT INTO appointment (id, patient_id, practitioner_id, treatment_id, starts_at, ends_at, status,
                            hold_expires_at, price_cents, deposit_cents, created_at, updated_at)
                        VALUES (:id, :patient, :practitioner, 'check-up', CAST(:from AS timestamptz), CAST(:to AS timestamptz),
                            :status, now(), 100, 0, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("patient", patientId)
                .param("practitioner", practitioner)
                .param("from", "2027-03-09 " + from + "+00")
                .param("to", "2027-03-09 " + to + "+00")
                .param("status", status)
                .update();
    }

    private void insertWithMoney(int price, int deposit) {
        jdbc.sql(
                        """
                        INSERT INTO appointment (id, patient_id, practitioner_id, treatment_id, starts_at, ends_at, status,
                            price_cents, deposit_cents, created_at, updated_at)
                        VALUES (:id, :patient, 'ana', 'check-up', now(), now() + interval '1 hour', 'CONFIRMED',
                            :price, :deposit, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("patient", patient)
                .param("price", price)
                .param("deposit", deposit)
                .update();
    }

    private void insertWithExpiry(String status, Object expiry) {
        jdbc.sql(
                        """
                        INSERT INTO appointment (id, patient_id, practitioner_id, treatment_id, starts_at, ends_at, status,
                            hold_expires_at, price_cents, deposit_cents, created_at, updated_at)
                        VALUES (:id, :patient, 'ana', 'check-up', now(), now() + interval '1 hour', :status,
                            CAST(:expiry AS timestamptz), 100, 0, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("patient", patient)
                .param("status", status)
                .param("expiry", expiry)
                .update();
    }
}
