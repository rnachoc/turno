-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- What the agents did, so that the clinic can read it after the agents service restarts. This holds
-- codes and usage figures only: what patients write stays out, on purpose. See docs/adr/0013.

CREATE TABLE conversation (
    id         uuid PRIMARY KEY,
    patient_id uuid NOT NULL REFERENCES patient (id),
    created_at timestamptz NOT NULL
);

CREATE INDEX conversation_recent_idx ON conversation (created_at DESC, id);

CREATE TABLE agent_step (
    conversation_id    uuid NOT NULL REFERENCES conversation (id),
    position           integer NOT NULL,
    kind               text NOT NULL,
    detail             text NOT NULL,
    input_tokens       integer NOT NULL,
    output_tokens      integer NOT NULL,
    estimated_cost_usd numeric(12, 6) NOT NULL,
    seconds            numeric(8, 2) NOT NULL,
    created_at         timestamptz NOT NULL,
    -- The agents service may send a step again if it never heard the answer.
    PRIMARY KEY (conversation_id, position),
    CONSTRAINT agent_step_valid_position CHECK (position >= 0),
    CONSTRAINT agent_step_valid_usage CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND estimated_cost_usd >= 0 AND seconds >= 0)
);

CREATE INDEX agent_step_created_idx ON agent_step (created_at);

CREATE TABLE handover (
    id              uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversation (id),
    patient_id      uuid NOT NULL REFERENCES patient (id),
    reason          text NOT NULL,
    created_at      timestamptz NOT NULL,
    resolved_at     timestamptz,
    resolved_by     text,
    CONSTRAINT handover_resolved_together CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);

-- An emergency must not hide behind an earlier handover of the same conversation for another reason,
-- so only the same reason counts as the same request for a person.
CREATE UNIQUE INDEX handover_one_open_per_reason
    ON handover (conversation_id, reason) WHERE resolved_at IS NULL;
CREATE INDEX handover_open_idx ON handover (created_at) WHERE resolved_at IS NULL;

GRANT SELECT, INSERT ON conversation TO turno_app;
GRANT SELECT, INSERT ON agent_step TO turno_app;
GRANT SELECT, INSERT, UPDATE ON handover TO turno_app;
