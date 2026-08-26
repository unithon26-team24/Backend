CREATE FUNCTION foundation_labels_valid(labels text[])
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    label text;
BEGIN
    IF labels IS NULL OR cardinality(labels) > 12 THEN
        RETURN false;
    END IF;
    FOREACH label IN ARRAY labels LOOP
        IF label IS NULL OR char_length(label) NOT BETWEEN 1 AND 64 OR label ~ '[[:cntrl:]]' THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END;
$$;

CREATE TABLE projects (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200 AND name !~ '[[:cntrl:]]'),
    goal text NOT NULL CHECK (char_length(goal) BETWEEN 1 AND 4000 AND goal !~ '[[:cntrl:]]'),
    final_deadline_at timestamptz NOT NULL,
    planning_time_zone text NOT NULL CHECK (char_length(planning_time_zone) BETWEEN 1 AND 100),
    owner_member_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'setup' CHECK (status IN ('setup', 'active', 'paused', 'completed', 'deleting')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id, owner_member_id)
);

CREATE FUNCTION foundation_validate_timezone()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.planning_time_zone) THEN
        RAISE EXCEPTION 'invalid IANA planning timezone';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER projects_validate_timezone
BEFORE INSERT OR UPDATE OF planning_time_zone ON projects
FOR EACH ROW EXECUTE FUNCTION foundation_validate_timezone();

CREATE TABLE project_members (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    slack_user_id text NOT NULL CHECK (char_length(slack_user_id) BETWEEN 1 AND 128 AND slack_user_id !~ '[[:space:][:cntrl:]]'),
    display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]'),
    member_role text NOT NULL CHECK (member_role IN ('owner', 'member')),
    responsibility_labels text[] NOT NULL DEFAULT '{}' CHECK (foundation_labels_valid(responsibility_labels)),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (project_id, id),
    UNIQUE (project_id, slack_user_id)
);

CREATE UNIQUE INDEX project_members_one_owner
ON project_members(project_id)
WHERE member_role = 'owner';

ALTER TABLE projects
ADD CONSTRAINT projects_owner_member_fk
FOREIGN KEY (id, owner_member_id)
REFERENCES project_members(project_id, id)
DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION foundation_validate_owner_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM project_members
        WHERE project_id = NEW.id AND id = NEW.owner_member_id AND member_role = 'owner'
    ) THEN
        RAISE EXCEPTION 'project owner_member_id must reference its owner member';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER projects_validate_owner_role
AFTER INSERT OR UPDATE OF owner_member_id ON projects
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION foundation_validate_owner_role();

CREATE FUNCTION foundation_validate_member_owner_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_project_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        checked_project_id := OLD.project_id;
    ELSE
        checked_project_id := NEW.project_id;
    END IF;
    IF EXISTS (SELECT 1 FROM projects WHERE id = checked_project_id)
        AND NOT EXISTS (
            SELECT 1
            FROM projects p
            JOIN project_members pm
              ON pm.project_id = p.id
             AND pm.id = p.owner_member_id
             AND pm.member_role = 'owner'
            WHERE p.id = checked_project_id
        ) THEN
        RAISE EXCEPTION 'project must retain exactly one owner member';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE CONSTRAINT TRIGGER project_members_validate_owner_role
AFTER UPDATE OR DELETE ON project_members
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION foundation_validate_member_owner_role();

CREATE TABLE project_parts (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120 AND name !~ '[[:cntrl:]]'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (project_id, id),
    UNIQUE (project_id, name)
);

CREATE TABLE project_part_memberships (
    project_id uuid NOT NULL,
    part_id uuid NOT NULL,
    member_id uuid NOT NULL,
    part_role text NOT NULL CHECK (part_role IN ('part_lead', 'member')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (part_id, member_id),
    FOREIGN KEY (project_id, part_id) REFERENCES project_parts(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, member_id) REFERENCES project_members(project_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX project_parts_one_lead
ON project_part_memberships(part_id)
WHERE part_role = 'part_lead';

CREATE TABLE project_settings (
    project_id uuid PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    llm_mode text NOT NULL DEFAULT 'active' CHECK (llm_mode IN ('active', 'rule_only')),
    settings_version bigint NOT NULL DEFAULT 1 CHECK (settings_version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE project_resource_references (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('slack', 'notion')),
    resource_type text NOT NULL,
    logical_reference text NOT NULL CHECK (
        char_length(logical_reference) BETWEEN 1 AND 255
        AND logical_reference !~ '[[:space:][:cntrl:]]'
    ),
    selected boolean NOT NULL DEFAULT true CHECK (selected),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((provider = 'slack' AND resource_type = 'slack_channel')
        OR (provider = 'notion' AND resource_type = 'notion_parent')),
    UNIQUE (project_id, provider, resource_type)
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    actor_member_id uuid,
    event_type text NOT NULL CHECK (char_length(event_type) BETWEEN 1 AND 100 AND event_type !~ '[[:space:][:cntrl:]]'),
    subject_type text NOT NULL CHECK (char_length(subject_type) BETWEEN 1 AND 100 AND subject_type !~ '[[:space:][:cntrl:]]'),
    subject_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (project_id, actor_member_id) REFERENCES project_members(project_id, id)
);

CREATE FUNCTION foundation_reject_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit events are append-only';
END;
$$;

CREATE TRIGGER audit_events_append_only
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION foundation_reject_audit_mutation();

CREATE TABLE automation_jobs (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    job_type text NOT NULL CHECK (char_length(job_type) BETWEEN 1 AND 100 AND job_type !~ '[[:space:][:cntrl:]]'),
    job_key text NOT NULL UNIQUE CHECK (char_length(job_key) BETWEEN 1 AND 500 AND job_key !~ '[[:cntrl:]]'),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'cancelled')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    run_after timestamptz NOT NULL,
    lease_owner text CHECK (lease_owner IS NULL OR (char_length(lease_owner) BETWEEN 1 AND 200 AND lease_owner !~ '[[:cntrl:]]')),
    leased_at timestamptz,
    lease_expires_at timestamptz,
    finished_at timestamptz,
    failure_category text CHECK (failure_category IS NULL OR char_length(failure_category) BETWEEN 1 AND 100),
    meeting_id uuid,
    agenda_id uuid,
    timer_phase text,
    agenda_starts_at timestamptz,
    agenda_duration_seconds integer,
    recurrence_rule text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'running' AND lease_owner IS NOT NULL AND leased_at IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'running' AND lease_owner IS NULL AND leased_at IS NULL AND lease_expires_at IS NULL)),
    CHECK ((status IN ('succeeded', 'failed', 'cancelled')) = (finished_at IS NOT NULL)),
    CHECK (lease_expires_at IS NULL OR lease_expires_at = leased_at + interval '90 seconds'),
    CHECK (recurrence_rule IS NULL),
    CHECK (
        (job_type = 'meeting_agenda_timer'
            AND meeting_id IS NOT NULL
            AND agenda_id IS NOT NULL
            AND timer_phase IN ('80_percent', 'expiry')
            AND agenda_starts_at IS NOT NULL
            AND agenda_duration_seconds > 0
            AND job_key = concat('meeting_agenda_timer:', meeting_id, ':', agenda_id, ':', timer_phase))
        OR
        (job_type <> 'meeting_agenda_timer'
            AND meeting_id IS NULL
            AND agenda_id IS NULL
            AND timer_phase IS NULL
            AND agenda_starts_at IS NULL
            AND agenda_duration_seconds IS NULL)
    )
);

CREATE INDEX automation_jobs_claimable
ON automation_jobs(status, run_after, created_at);
