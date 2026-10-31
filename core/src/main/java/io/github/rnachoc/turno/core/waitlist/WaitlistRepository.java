package io.github.rnachoc.turno.core.waitlist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores the waitlist, the freed slots and the offers made.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
public class WaitlistRepository {

    /** An offer and the appointment behind it. */
    record OfferState(UUID id, UUID appointmentId, String appointmentStatus, Instant holdExpiresAt) {}

    private final JdbcClient jdbc;

    WaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an entry unless the patient is already waiting for that treatment.
     *
     * @return whether it was inserted
     */
    boolean insertIfNotWaiting(WaitlistEntry entry) {
        return jdbc.sql(
                                """
                                INSERT INTO waitlist_entry (id, patient_id, treatment_id, practitioner_id, status, created_at)
                                VALUES (:id, :patientId, :treatmentId, :practitionerId, 'WAITING', :createdAt)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("id", entry.id())
                        .param("patientId", entry.patientId())
                        .param("treatmentId", entry.treatmentId())
                        .param("practitionerId", entry.practitionerId())
                        .param("createdAt", timestamp(entry.createdAt()))
                        .update()
                == 1;
    }

    boolean remove(UUID entryId, UUID patientId) {
        return jdbc.sql(
                                """
                                UPDATE waitlist_entry SET status = 'REMOVED'
                                WHERE id = :id AND patient_id = :patientId AND status = 'WAITING'
                                """)
                        .param("id", entryId)
                        .param("patientId", patientId)
                        .update()
                == 1;
    }

    List<WaitlistEntry> listForPatient(UUID patientId) {
        return jdbc.sql("SELECT * FROM waitlist_entry WHERE patient_id = :patientId ORDER BY created_at, id")
                .param("patientId", patientId)
                .query(WaitlistRepository::mapEntry)
                .list();
    }

    /** Lists who is waiting and has not been asked about this slot yet, the longest wait first. */
    List<WaitlistEntry> queueFor(UUID freedSlotId) {
        return jdbc.sql(
                        """
                        SELECT e.* FROM waitlist_entry e
                        WHERE e.status = 'WAITING'
                          AND NOT EXISTS (SELECT 1 FROM slot_offer o
                                          WHERE o.freed_slot_id = :slot AND o.patient_id = e.patient_id)
                        ORDER BY e.created_at, e.id
                        """)
                .param("slot", freedSlotId)
                .query(WaitlistRepository::mapEntry)
                .list();
    }

    /**
     * Records a slot that has just become free. It must run in the transaction that frees it.
     *
     * @param slot the slot
     * @param now the current instant
     */
    public void recordFreedSlot(FreedSlot slot, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO freed_slot (id, practitioner_id, starts_at, ends_at, created_at)
                        VALUES (:id, :practitionerId, :startsAt, :endsAt, :now)
                        """)
                .param("id", slot.id())
                .param("practitionerId", slot.practitionerId())
                .param("startsAt", timestamp(slot.startsAt()))
                .param("endsAt", timestamp(slot.endsAt()))
                .param("now", timestamp(now))
                .update();
    }

    List<FreedSlot> pendingSlots(int limit) {
        return jdbc.sql(
                        """
                        SELECT * FROM freed_slot WHERE picked_up_at IS NULL
                        ORDER BY created_at, id LIMIT :limit
                        """)
                .param("limit", limit)
                .query(WaitlistRepository::mapSlot)
                .list();
    }

    boolean markPickedUp(UUID slotId, Instant now) {
        return jdbc.sql("UPDATE freed_slot SET picked_up_at = COALESCE(picked_up_at, :now) WHERE id = :id")
                        .param("now", timestamp(now))
                        .param("id", slotId)
                        .update()
                == 1;
    }

    Optional<FreedSlot> lockSlot(UUID slotId) {
        return jdbc.sql("SELECT * FROM freed_slot WHERE id = :id FOR UPDATE")
                .param("id", slotId)
                .query(WaitlistRepository::mapSlot)
                .optional();
    }

    int countOffers(UUID slotId) {
        return jdbc.sql("SELECT count(*) FROM slot_offer WHERE freed_slot_id = :slot")
                .param("slot", slotId)
                .query(Integer.class)
                .single();
    }

    int countOffersToPatientSince(UUID patientId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM slot_offer WHERE patient_id = :patient AND created_at >= :since")
                .param("patient", patientId)
                .param("since", timestamp(since))
                .query(Integer.class)
                .single();
    }

    /** Finds an offer of this slot whose hold is still running, so that a retry does not make another. */
    Optional<OfferState> findRunningOffer(UUID slotId, Instant now) {
        return jdbc.sql(
                        """
                        SELECT o.id, o.appointment_id, a.status, a.hold_expires_at
                        FROM slot_offer o JOIN appointment a ON a.id = o.appointment_id
                        WHERE o.freed_slot_id = :slot AND a.status = 'HOLD' AND a.hold_expires_at > :now
                        """)
                .param("slot", slotId)
                .param("now", timestamp(now))
                .query(WaitlistRepository::mapOffer)
                .optional();
    }

    Optional<OfferState> findOffer(UUID offerId) {
        return jdbc.sql(
                        """
                        SELECT o.id, o.appointment_id, a.status, a.hold_expires_at
                        FROM slot_offer o JOIN appointment a ON a.id = o.appointment_id
                        WHERE o.id = :id
                        """)
                .param("id", offerId)
                .query(WaitlistRepository::mapOffer)
                .optional();
    }

    void insertOffer(UUID id, UUID slotId, UUID entryId, UUID patientId, UUID appointmentId, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO slot_offer (id, freed_slot_id, waitlist_entry_id, patient_id, appointment_id, created_at)
                        VALUES (:id, :slot, :entry, :patient, :appointment, :now)
                        """)
                .param("id", id)
                .param("slot", slotId)
                .param("entry", entryId)
                .param("patient", patientId)
                .param("appointment", appointmentId)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Takes a patient off the list once an offer made to them has become a booking.
     *
     * @param appointmentId the appointment that was just confirmed
     * @return whether that appointment came from an offer
     */
    public boolean markBookedByAppointment(UUID appointmentId) {
        return jdbc.sql(
                                """
                                UPDATE waitlist_entry SET status = 'BOOKED'
                                WHERE status = 'WAITING' AND id IN (
                                    SELECT waitlist_entry_id FROM slot_offer WHERE appointment_id = :appointment)
                                """)
                        .param("appointment", appointmentId)
                        .update()
                == 1;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static WaitlistEntry mapEntry(ResultSet rs, int row) throws SQLException {
        return new WaitlistEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("treatment_id"),
                rs.getString("practitioner_id"),
                rs.getString("status"),
                instant(rs, "created_at"));
    }

    private static FreedSlot mapSlot(ResultSet rs, int row) throws SQLException {
        return new FreedSlot(
                rs.getObject("id", UUID.class),
                rs.getString("practitioner_id"),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"));
    }

    private static OfferState mapOffer(ResultSet rs, int row) throws SQLException {
        OffsetDateTime expires = rs.getObject("hold_expires_at", OffsetDateTime.class);
        return new OfferState(
                rs.getObject("id", UUID.class),
                rs.getObject("appointment_id", UUID.class),
                rs.getString("status"),
                expires == null ? Instant.EPOCH : expires.toInstant());
    }
}
