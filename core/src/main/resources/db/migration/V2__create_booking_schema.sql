-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- Schema of the system of record. The rules that must hold under any concurrency live here, in
-- the database, and not only in application code. See docs/adr/0006.

CREATE TABLE patient (
    id         uuid PRIMARY KEY,
    full_name  text NOT NULL,
    phone      text NOT NULL UNIQUE,
    email      text,
    language   text NOT NULL,
    created_at timestamptz NOT NULL
);

-- Treatments and practitioners live in the tenant file, so they are plain identifiers here. Price,
-- deposit and duration are copied at booking time: a later price change must not alter a booking.
CREATE TABLE appointment (
    id                uuid PRIMARY KEY,
    patient_id        uuid NOT NULL REFERENCES patient (id),
    practitioner_id   text NOT NULL,
    treatment_id      text NOT NULL,
    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz NOT NULL,
    status            text NOT NULL,
    hold_expires_at   timestamptz,
    price_cents       integer NOT NULL,
    deposit_cents     integer NOT NULL,
    deposit_forfeited boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT appointment_valid_range CHECK (ends_at > starts_at),
    CONSTRAINT appointment_valid_money CHECK (price_cents >= 0 AND deposit_cents BETWEEN 0 AND price_cents),
    CONSTRAINT appointment_valid_status CHECK (
        status IN ('HOLD', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT appointment_hold_has_expiry CHECK (status <> 'HOLD' OR hold_expires_at IS NOT NULL),

    -- Half-open ranges, so an appointment may start exactly when the previous one ends.
    CONSTRAINT appointment_no_practitioner_overlap EXCLUDE USING gist (
        practitioner_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('HOLD', 'CONFIRMED')),
    CONSTRAINT appointment_no_patient_overlap EXCLUDE USING gist (
        patient_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('HOLD', 'CONFIRMED'))
);

CREATE INDEX appointment_patient_idx ON appointment (patient_id, starts_at);
CREATE INDEX appointment_expired_holds_idx ON appointment (hold_expires_at) WHERE status = 'HOLD';

-- Written in the same transaction as the operation it protects, so a retry either finds the stored
-- response or runs the operation from scratch, and it can never run twice.
CREATE TABLE idempotency_key (
    principal   text NOT NULL,
    key         text NOT NULL,
    fingerprint text NOT NULL,
    status      integer,
    response    text,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (principal, key)
);

CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);

CREATE TABLE audit_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor       text NOT NULL,
    action      text NOT NULL,
    outcome     text NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    entity_id   text,
    patient_id  uuid,
    details     jsonb NOT NULL
);

CREATE INDEX audit_event_entity_idx ON audit_event (entity_id);
CREATE INDEX audit_event_patient_idx ON audit_event (patient_id);

CREATE FUNCTION reject_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$;

CREATE TRIGGER audit_event_no_change
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();
