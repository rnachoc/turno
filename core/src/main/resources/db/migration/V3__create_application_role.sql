-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- The service runs as this role, which owns nothing. Unlike the owner, it cannot disable the audit
-- trigger, drop a table or read the migration history, so a compromised service cannot rewrite the
-- past. Each new table needs its own grants here: there are no default privileges on purpose.
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'turno_app') THEN
            -- It gets its password and the right to log in from afterMigrate.sql.
            CREATE ROLE turno_app NOLOGIN;
        END IF;
    END
$$;

GRANT USAGE ON SCHEMA public TO turno_app;

-- UPDATE on patient is needed by SELECT ... FOR UPDATE, which serializes a patient's bookings.
GRANT SELECT, INSERT, UPDATE ON patient TO turno_app;
GRANT SELECT, INSERT, UPDATE ON appointment TO turno_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_key TO turno_app;
GRANT SELECT, INSERT ON audit_event TO turno_app;
