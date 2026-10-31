package io.github.rnachoc.turno.core.patient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores patients. There is deliberately no way to list them: they can only be reached by exact
 * identifier or phone number, which limits what a manipulated agent could extract.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
public class PatientRepository {

    private final JdbcClient jdbc;

    PatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a patient unless the phone number is already taken.
     *
     * @param patient the patient
     * @return whether the patient was inserted
     */
    boolean insertIfPhoneIsFree(Patient patient) {
        return jdbc.sql(
                                """
                                INSERT INTO patient (id, full_name, phone, email, language, created_at)
                                VALUES (:id, :fullName, :phone, :email, :language, :createdAt)
                                ON CONFLICT (phone) DO NOTHING
                                """)
                        .param("id", patient.id())
                        .param("fullName", patient.fullName())
                        .param("phone", patient.phone())
                        .param("email", patient.email())
                        .param("language", patient.language())
                        .param("createdAt", OffsetDateTime.ofInstant(patient.createdAt(), ZoneOffset.UTC))
                        .update()
                == 1;
    }

    Optional<Patient> findById(UUID id) {
        return jdbc.sql("SELECT * FROM patient WHERE id = :id")
                .param("id", id)
                .query(PatientRepository::map)
                .optional();
    }

    Optional<Patient> findByPhone(String phone) {
        return jdbc.sql("SELECT * FROM patient WHERE phone = :phone")
                .param("phone", phone)
                .query(PatientRepository::map)
                .optional();
    }

    /**
     * Finds the one patient with an address. Nothing makes addresses unique, and if two people share
     * one, an email cannot say which of them is writing.
     *
     * @param email the address
     * @return the patient, if exactly one has it
     */
    Optional<Patient> findByEmail(String email) {
        List<Patient> found =
                jdbc.sql("SELECT * FROM patient WHERE lower(email) = lower(:email) LIMIT 2")
                        .param("email", email)
                        .query(PatientRepository::map)
                        .list();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * Locks a patient until the end of the transaction, which serialises every booking operation of
     * that patient and always takes the locks in the same order.
     *
     * @param id the patient
     * @return whether the patient exists
     */
    public boolean lock(UUID id) {
        return jdbc.sql("SELECT id FROM patient WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private static Patient map(ResultSet rs, int row) throws SQLException {
        return new Patient(
                rs.getObject("id", UUID.class),
                rs.getString("full_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("language"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
