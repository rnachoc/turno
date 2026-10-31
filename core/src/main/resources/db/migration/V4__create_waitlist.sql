-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- The waitlist, and the slots freed by a cancellation. A freed slot is written in the same
-- transaction as the cancellation, so a worker can never miss one or see one that did not happen.
-- See docs/adr/0012.

CREATE TABLE waitlist_entry (
    id              uuid PRIMARY KEY,
    patient_id      uuid NOT NULL REFERENCES patient (id),
    treatment_id    text NOT NULL,
    practitioner_id text,
    status          text NOT NULL CHECK (status IN ('WAITING', 'BOOKED', 'REMOVED')),
    created_at      timestamptz NOT NULL
);

-- Waiting twice for the same treatment would mean being offered the same slots twice.
CREATE UNIQUE INDEX waitlist_one_entry_per_treatment
    ON waitlist_entry (patient_id, treatment_id) WHERE status = 'WAITING';
CREATE INDEX waitlist_queue_idx ON waitlist_entry (created_at) WHERE status = 'WAITING';

CREATE TABLE freed_slot (
    id              uuid PRIMARY KEY,
    practitioner_id text NOT NULL,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    picked_up_at    timestamptz,
    CONSTRAINT freed_slot_valid_range CHECK (ends_at > starts_at)
);

CREATE INDEX freed_slot_pending_idx ON freed_slot (created_at) WHERE picked_up_at IS NULL;

-- An offer is an ordinary hold, so accepting one goes through the rules that confirmations already
-- follow. This table only remembers who was offered what.
CREATE TABLE slot_offer (
    id                uuid PRIMARY KEY,
    freed_slot_id     uuid NOT NULL REFERENCES freed_slot (id),
    waitlist_entry_id uuid NOT NULL REFERENCES waitlist_entry (id),
    patient_id        uuid NOT NULL REFERENCES patient (id),
    appointment_id    uuid NOT NULL UNIQUE REFERENCES appointment (id),
    created_at        timestamptz NOT NULL,
    -- The same person is not asked twice about the same slot.
    UNIQUE (freed_slot_id, patient_id)
);

CREATE INDEX slot_offer_patient_idx ON slot_offer (patient_id, created_at);

GRANT SELECT, INSERT, UPDATE ON waitlist_entry TO turno_app;
GRANT SELECT, INSERT, UPDATE ON freed_slot TO turno_app;
GRANT SELECT, INSERT ON slot_offer TO turno_app;
