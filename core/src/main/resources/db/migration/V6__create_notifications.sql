-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- What the clinic has to tell a patient. It is written in the same transaction as what it announces,
-- so an offer can never exist without its notice, or a notice without its offer. See docs/adr/0016.

CREATE TABLE notification (
    id             uuid PRIMARY KEY,
    kind           text NOT NULL CHECK (kind IN ('SLOT_OFFER')),
    patient_id     uuid NOT NULL REFERENCES patient (id),
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment (id),
    created_at     timestamptz NOT NULL,
    sent_at        timestamptz
);

CREATE INDEX notification_pending_idx ON notification (created_at) WHERE sent_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON notification TO turno_app;
