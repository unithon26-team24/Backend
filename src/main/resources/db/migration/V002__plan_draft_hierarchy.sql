CREATE FUNCTION plan_draft_plain_text(value text, maximum_length integer)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT value IS NOT NULL
       AND char_length(value) BETWEEN 1 AND maximum_length
       AND value !~ '[[:cntrl:]]'
$$;

CREATE FUNCTION plan_draft_client_key_valid(value text)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT plan_draft_plain_text(value, 120) AND value !~ '[[:space:]]'
$$;

CREATE TABLE plan_drafts (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    current_revision_id uuid,
    status text NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'review_requested', 'approved', 'rejected', 'superseded')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id, project_id)
);

CREATE TABLE draft_revisions (
    id uuid PRIMARY KEY,
    plan_draft_id uuid NOT NULL,
    project_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_by_member_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    sealed_at timestamptz,
    FOREIGN KEY (plan_draft_id, project_id)
        REFERENCES plan_drafts(id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, created_by_member_id)
        REFERENCES project_members(project_id, id),
    UNIQUE (plan_draft_id, revision_number),
    UNIQUE (plan_draft_id, id),
    UNIQUE (project_id, id),
    UNIQUE (id, content_hash),
    UNIQUE (plan_draft_id, id, content_hash)
);

ALTER TABLE plan_drafts
ADD CONSTRAINT plan_drafts_current_revision_fk
FOREIGN KEY (id, current_revision_id)
REFERENCES draft_revisions(plan_draft_id, id)
DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE plan_draft_success_criteria (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    client_key text NOT NULL CHECK (plan_draft_client_key_valid(client_key)),
    statement text NOT NULL CHECK (plan_draft_plain_text(statement, 2000)),
    verification_method text NOT NULL CHECK (plan_draft_plain_text(verification_method, 2000)),
    short_rationale text NOT NULL CHECK (plan_draft_plain_text(short_rationale, 2000)),
    confirmation_status text NOT NULL
        CHECK (confirmation_status IN ('needs_confirmation', 'accepted', 'rejected')),
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE,
    UNIQUE (draft_revision_id, id),
    UNIQUE (draft_revision_id, client_key)
);

CREATE TABLE plan_draft_member_responsibilities (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    member_id uuid NOT NULL,
    labels text[] NOT NULL CHECK (foundation_labels_valid(labels)),
    short_rationale text NOT NULL CHECK (plan_draft_plain_text(short_rationale, 2000)),
    confirmation_status text NOT NULL
        CHECK (confirmation_status IN ('needs_confirmation', 'accepted', 'rejected')),
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, member_id)
        REFERENCES project_members(project_id, id),
    UNIQUE (draft_revision_id, member_id)
);

CREATE TABLE plan_draft_assumptions (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    statement text NOT NULL CHECK (plan_draft_plain_text(statement, 2000)),
    why_it_matters text NOT NULL CHECK (plan_draft_plain_text(why_it_matters, 2000)),
    needs_owner_confirmation boolean NOT NULL,
    confirmation_status text NOT NULL
        CHECK (confirmation_status IN ('needs_confirmation', 'accepted', 'resolved')),
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE
);

CREATE TABLE plan_draft_milestones (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    client_key text NOT NULL CHECK (plan_draft_client_key_valid(client_key)),
    position integer NOT NULL CHECK (position > 0),
    title text NOT NULL CHECK (plan_draft_plain_text(title, 240)),
    target_at timestamptz NOT NULL,
    deliverable text NOT NULL CHECK (plan_draft_plain_text(deliverable, 4000)),
    short_rationale text NOT NULL CHECK (plan_draft_plain_text(short_rationale, 2000)),
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE,
    UNIQUE (draft_revision_id, id),
    UNIQUE (draft_revision_id, client_key),
    UNIQUE (draft_revision_id, position)
);

CREATE TABLE plan_draft_milestone_criteria (
    draft_revision_id uuid NOT NULL,
    milestone_id uuid NOT NULL,
    success_criterion_id uuid NOT NULL,
    PRIMARY KEY (draft_revision_id, milestone_id, success_criterion_id),
    FOREIGN KEY (draft_revision_id, milestone_id)
        REFERENCES plan_draft_milestones(draft_revision_id, id) ON DELETE CASCADE,
    FOREIGN KEY (draft_revision_id, success_criterion_id)
        REFERENCES plan_draft_success_criteria(draft_revision_id, id) ON DELETE CASCADE
);

CREATE TABLE plan_draft_meeting_blueprints (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    milestone_id uuid NOT NULL,
    part_id uuid,
    meeting_type text NOT NULL
        CHECK (meeting_type IN ('kickoff', 'milestone_checkpoint', 'risk_resolution')),
    checkpoint_phase text
        CHECK (checkpoint_phase IS NULL OR checkpoint_phase IN ('readiness', 'review')),
    target_window text NOT NULL CHECK (plan_draft_plain_text(target_window, 500)),
    duration_seconds integer NOT NULL CHECK (duration_seconds BETWEEN 900 AND 5400),
    trigger_rules jsonb NOT NULL CHECK (jsonb_typeof(trigger_rules) = 'object'),
    purpose text NOT NULL CHECK (plan_draft_plain_text(purpose, 2000)),
    must_decide_items text[] NOT NULL CHECK (cardinality(must_decide_items) > 0),
    exit_gate text NOT NULL CHECK (plan_draft_plain_text(exit_gate, 2000)),
    meeting_owner_member_id uuid NOT NULL,
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (draft_revision_id, milestone_id)
        REFERENCES plan_draft_milestones(draft_revision_id, id),
    FOREIGN KEY (project_id, meeting_owner_member_id)
        REFERENCES project_members(project_id, id),
    FOREIGN KEY (project_id, part_id)
        REFERENCES project_parts(project_id, id),
    CHECK ((meeting_type = 'milestone_checkpoint' AND checkpoint_phase IS NOT NULL)
        OR (meeting_type <> 'milestone_checkpoint' AND checkpoint_phase IS NULL)),
    UNIQUE (draft_revision_id, id)
);

CREATE TABLE plan_draft_agenda_items (
    id uuid PRIMARY KEY,
    draft_revision_id uuid NOT NULL,
    meeting_blueprint_id uuid NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    title text NOT NULL CHECK (plan_draft_plain_text(title, 500)),
    allocated_seconds integer NOT NULL CHECK (allocated_seconds > 0),
    FOREIGN KEY (draft_revision_id, meeting_blueprint_id)
        REFERENCES plan_draft_meeting_blueprints(draft_revision_id, id) ON DELETE CASCADE,
    UNIQUE (meeting_blueprint_id, position)
);

CREATE TABLE plan_draft_action_templates (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    client_key text NOT NULL CHECK (plan_draft_client_key_valid(client_key)),
    milestone_id uuid NOT NULL,
    title text NOT NULL CHECK (plan_draft_plain_text(title, 500)),
    description text NOT NULL CHECK (plan_draft_plain_text(description, 4000)),
    candidate_member_id uuid,
    needs_owner_selection boolean NOT NULL,
    due_at timestamptz NOT NULL,
    definition_of_done text NOT NULL CHECK (plan_draft_plain_text(definition_of_done, 4000)),
    required_labels text[] NOT NULL CHECK (foundation_labels_valid(required_labels)),
    short_rationale text NOT NULL CHECK (plan_draft_plain_text(short_rationale, 2000)),
    FOREIGN KEY (project_id, draft_revision_id)
        REFERENCES draft_revisions(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (draft_revision_id, milestone_id)
        REFERENCES plan_draft_milestones(draft_revision_id, id),
    FOREIGN KEY (project_id, candidate_member_id)
        REFERENCES project_members(project_id, id),
    CHECK ((candidate_member_id IS NULL AND needs_owner_selection)
        OR (candidate_member_id IS NOT NULL AND NOT needs_owner_selection)),
    UNIQUE (draft_revision_id, id),
    UNIQUE (draft_revision_id, client_key)
);

CREATE TABLE plan_draft_template_assignments (
    id uuid PRIMARY KEY,
    plan_draft_id uuid NOT NULL,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    revision_content_hash text NOT NULL,
    template_client_key text NOT NULL,
    selected_member_id uuid NOT NULL,
    selected_by_member_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (plan_draft_id, project_id)
        REFERENCES plan_drafts(id, project_id),
    FOREIGN KEY (plan_draft_id, draft_revision_id, revision_content_hash)
        REFERENCES draft_revisions(plan_draft_id, id, content_hash),
    FOREIGN KEY (draft_revision_id, template_client_key)
        REFERENCES plan_draft_action_templates(draft_revision_id, client_key),
    FOREIGN KEY (project_id, selected_member_id)
        REFERENCES project_members(project_id, id),
    FOREIGN KEY (project_id, selected_by_member_id)
        REFERENCES project_members(project_id, id),
    UNIQUE (draft_revision_id, template_client_key)
);

CREATE TABLE plan_draft_responsibility_label_confirmations (
    id uuid PRIMARY KEY,
    plan_draft_id uuid NOT NULL,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    revision_content_hash text NOT NULL,
    member_id uuid NOT NULL,
    labels text[] NOT NULL CHECK (foundation_labels_valid(labels)),
    decision text NOT NULL CHECK (decision IN ('accepted', 'rejected')),
    confirmed_by_member_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (plan_draft_id, project_id)
        REFERENCES plan_drafts(id, project_id),
    FOREIGN KEY (plan_draft_id, draft_revision_id, revision_content_hash)
        REFERENCES draft_revisions(plan_draft_id, id, content_hash),
    FOREIGN KEY (project_id, member_id)
        REFERENCES project_members(project_id, id),
    FOREIGN KEY (project_id, confirmed_by_member_id)
        REFERENCES project_members(project_id, id),
    UNIQUE (draft_revision_id, member_id)
);

CREATE TABLE plan_draft_approvals (
    id uuid PRIMARY KEY,
    plan_draft_id uuid NOT NULL,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    revision_content_hash text NOT NULL,
    approval_scope text NOT NULL CHECK (approval_scope IN ('project', 'part')),
    part_id uuid,
    actor_member_id uuid NOT NULL,
    decision text NOT NULL CHECK (decision IN ('approved', 'changes_requested', 'rejected')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (plan_draft_id, project_id)
        REFERENCES plan_drafts(id, project_id),
    FOREIGN KEY (plan_draft_id, draft_revision_id, revision_content_hash)
        REFERENCES draft_revisions(plan_draft_id, id, content_hash),
    FOREIGN KEY (project_id, actor_member_id)
        REFERENCES project_members(project_id, id),
    FOREIGN KEY (project_id, part_id)
        REFERENCES project_parts(project_id, id),
    CHECK ((approval_scope = 'project' AND part_id IS NULL)
        OR (approval_scope = 'part' AND part_id IS NOT NULL))
);

CREATE UNIQUE INDEX plan_draft_approvals_one_scope_per_revision
ON plan_draft_approvals(draft_revision_id, approval_scope, COALESCE(part_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TABLE plan_draft_revision_audit_events (
    id uuid PRIMARY KEY,
    plan_draft_id uuid NOT NULL,
    project_id uuid NOT NULL,
    draft_revision_id uuid NOT NULL,
    revision_content_hash text NOT NULL,
    actor_member_id uuid NOT NULL,
    event_type text NOT NULL CHECK (plan_draft_client_key_valid(event_type)),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (plan_draft_id, project_id)
        REFERENCES plan_drafts(id, project_id),
    FOREIGN KEY (plan_draft_id, draft_revision_id, revision_content_hash)
        REFERENCES draft_revisions(plan_draft_id, id, content_hash),
    FOREIGN KEY (project_id, actor_member_id)
        REFERENCES project_members(project_id, id)
);

CREATE FUNCTION plan_draft_reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'plan draft revision data is immutable';
END;
$$;

CREATE FUNCTION plan_draft_seal_revision_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.sealed_at IS NULL
        AND NEW.sealed_at IS NOT NULL
        AND NEW.id = OLD.id
        AND NEW.plan_draft_id = OLD.plan_draft_id
        AND NEW.project_id = OLD.project_id
        AND NEW.revision_number = OLD.revision_number
        AND NEW.content_hash = OLD.content_hash
        AND NEW.created_by_member_id = OLD.created_by_member_id
        AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'draft revision is immutable';
END;
$$;

CREATE TRIGGER draft_revisions_immutable
BEFORE UPDATE ON draft_revisions
FOR EACH ROW EXECUTE FUNCTION plan_draft_seal_revision_only();

CREATE TRIGGER draft_revisions_reject_delete
BEFORE DELETE ON draft_revisions
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE FUNCTION plan_draft_reject_sealed_revision_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM draft_revisions
        WHERE id = NEW.draft_revision_id AND sealed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'cannot add data to sealed draft revision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_draft_success_criteria_reject_sealed
BEFORE INSERT ON plan_draft_success_criteria
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_member_responsibilities_reject_sealed
BEFORE INSERT ON plan_draft_member_responsibilities
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_assumptions_reject_sealed
BEFORE INSERT ON plan_draft_assumptions
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_milestones_reject_sealed
BEFORE INSERT ON plan_draft_milestones
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_milestone_criteria_reject_sealed
BEFORE INSERT ON plan_draft_milestone_criteria
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_meeting_blueprints_reject_sealed
BEFORE INSERT ON plan_draft_meeting_blueprints
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_agenda_items_reject_sealed
BEFORE INSERT ON plan_draft_agenda_items
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_action_templates_reject_sealed
BEFORE INSERT ON plan_draft_action_templates
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_template_assignments_reject_sealed
BEFORE INSERT ON plan_draft_template_assignments
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_responsibility_confirmations_reject_sealed
BEFORE INSERT ON plan_draft_responsibility_label_confirmations
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_sealed_revision_insert();

CREATE TRIGGER plan_draft_success_criteria_immutable
BEFORE UPDATE OR DELETE ON plan_draft_success_criteria
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_member_responsibilities_immutable
BEFORE UPDATE OR DELETE ON plan_draft_member_responsibilities
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_assumptions_immutable
BEFORE UPDATE OR DELETE ON plan_draft_assumptions
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_milestones_immutable
BEFORE UPDATE OR DELETE ON plan_draft_milestones
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_milestone_criteria_immutable
BEFORE UPDATE OR DELETE ON plan_draft_milestone_criteria
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_meeting_blueprints_immutable
BEFORE UPDATE OR DELETE ON plan_draft_meeting_blueprints
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_agenda_items_immutable
BEFORE UPDATE OR DELETE ON plan_draft_agenda_items
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_action_templates_immutable
BEFORE UPDATE OR DELETE ON plan_draft_action_templates
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_template_assignments_append_only
BEFORE UPDATE OR DELETE ON plan_draft_template_assignments
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_responsibility_confirmations_append_only
BEFORE UPDATE OR DELETE ON plan_draft_responsibility_label_confirmations
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_approvals_append_only
BEFORE UPDATE OR DELETE ON plan_draft_approvals
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE TRIGGER plan_draft_revision_audit_events_append_only
BEFORE UPDATE OR DELETE ON plan_draft_revision_audit_events
FOR EACH ROW EXECUTE FUNCTION plan_draft_reject_immutable_mutation();

CREATE FUNCTION plan_draft_validate_revision_hierarchy()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_revision_id uuid;
BEGIN
    checked_revision_id := NEW.id;
    IF NOT EXISTS (
        SELECT 1 FROM plan_draft_success_criteria
        WHERE draft_revision_id = checked_revision_id
    ) OR NOT EXISTS (
        SELECT 1 FROM plan_draft_milestones
        WHERE draft_revision_id = checked_revision_id
    ) OR EXISTS (
        SELECT 1
        FROM plan_draft_success_criteria criterion
        WHERE criterion.draft_revision_id = checked_revision_id
          AND NOT EXISTS (
              SELECT 1 FROM plan_draft_milestone_criteria coverage
              WHERE coverage.draft_revision_id = checked_revision_id
                AND coverage.success_criterion_id = criterion.id
          )
    ) OR EXISTS (
        SELECT 1
        FROM plan_draft_milestones milestone
        WHERE milestone.draft_revision_id = checked_revision_id
          AND NOT EXISTS (
              SELECT 1 FROM plan_draft_milestone_criteria coverage
              WHERE coverage.draft_revision_id = checked_revision_id
                AND coverage.milestone_id = milestone.id
          )
    ) THEN
        RAISE EXCEPTION 'draft revision criterion coverage is incomplete';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER draft_revisions_validate_hierarchy
AFTER INSERT ON draft_revisions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_revision_hierarchy();

CREATE FUNCTION plan_draft_validate_blueprint_agenda()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_blueprint_id uuid;
    expected_duration integer;
    agenda_count integer;
    agenda_total bigint;
    last_position integer;
BEGIN
    IF TG_TABLE_NAME = 'plan_draft_meeting_blueprints' THEN
        checked_blueprint_id := NEW.id;
    ELSE
        checked_blueprint_id := NEW.meeting_blueprint_id;
    END IF;
    SELECT duration_seconds INTO expected_duration
    FROM plan_draft_meeting_blueprints
    WHERE id = checked_blueprint_id;
    SELECT count(*), COALESCE(sum(allocated_seconds), 0), COALESCE(max(position), 0)
    INTO agenda_count, agenda_total, last_position
    FROM plan_draft_agenda_items
    WHERE meeting_blueprint_id = checked_blueprint_id;
    IF agenda_count = 0 OR last_position <> agenda_count OR agenda_total <> expected_duration THEN
        RAISE EXCEPTION 'blueprint agenda must be ordered, nonempty, and total its duration';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER plan_draft_blueprints_validate_agenda
AFTER INSERT ON plan_draft_meeting_blueprints
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_blueprint_agenda();

CREATE CONSTRAINT TRIGGER plan_draft_agenda_items_validate_agenda
AFTER INSERT ON plan_draft_agenda_items
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_blueprint_agenda();

CREATE FUNCTION plan_draft_validate_meeting_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM project_members
        WHERE project_id = NEW.project_id
          AND id = NEW.meeting_owner_member_id
          AND is_active
    ) THEN
        RAISE EXCEPTION 'meeting owner must be an active project member';
    END IF;
    IF NEW.part_id IS NULL AND NOT EXISTS (
        SELECT 1 FROM projects
        WHERE id = NEW.project_id AND owner_member_id = NEW.meeting_owner_member_id
    ) THEN
        RAISE EXCEPTION 'project blueprint owner must be project owner';
    END IF;
    IF NEW.part_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM project_part_memberships
        WHERE project_id = NEW.project_id
          AND part_id = NEW.part_id
          AND member_id = NEW.meeting_owner_member_id
          AND part_role = 'part_lead'
    ) THEN
        RAISE EXCEPTION 'part blueprint owner must be part lead';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_draft_blueprints_validate_owner
BEFORE INSERT ON plan_draft_meeting_blueprints
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_meeting_owner();

CREATE FUNCTION plan_draft_validate_action_template()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    milestone_target timestamptz;
BEGIN
    SELECT target_at INTO milestone_target
    FROM plan_draft_milestones
    WHERE draft_revision_id = NEW.draft_revision_id AND id = NEW.milestone_id;
    IF milestone_target IS NULL OR NEW.due_at > milestone_target THEN
        RAISE EXCEPTION 'action template due date exceeds milestone target';
    END IF;
    IF NEW.candidate_member_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM project_members
        WHERE project_id = NEW.project_id AND id = NEW.candidate_member_id AND is_active
    ) THEN
        RAISE EXCEPTION 'action template candidate must be active';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_draft_action_templates_validate
BEFORE INSERT ON plan_draft_action_templates
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_action_template();

CREATE FUNCTION plan_draft_validate_current_revision_reference(
    checked_draft_id uuid,
    checked_revision_id uuid,
    checked_hash text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM plan_drafts draft
        JOIN draft_revisions revision
          ON revision.plan_draft_id = draft.id
         AND revision.id = draft.current_revision_id
        WHERE draft.id = checked_draft_id
          AND revision.id = checked_revision_id
          AND revision.content_hash = checked_hash
    )
$$;

CREATE FUNCTION plan_draft_validate_assignment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT plan_draft_validate_current_revision_reference(
            NEW.plan_draft_id, NEW.draft_revision_id, NEW.revision_content_hash) THEN
        RAISE EXCEPTION 'template assignment must reference current revision and content hash';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM projects
        WHERE id = NEW.project_id AND owner_member_id = NEW.selected_by_member_id
    ) THEN
        RAISE EXCEPTION 'template assignment actor must be project owner';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM project_members
        WHERE project_id = NEW.project_id AND id = NEW.selected_member_id AND is_active
    ) THEN
        RAISE EXCEPTION 'selected template owner must be active';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM plan_draft_action_templates
        WHERE draft_revision_id = NEW.draft_revision_id
          AND client_key = NEW.template_client_key
          AND candidate_member_id = NEW.selected_member_id
          AND NOT needs_owner_selection
    ) THEN
        RAISE EXCEPTION 'template assignment must match resolved template candidate';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_draft_template_assignments_validate
BEFORE INSERT ON plan_draft_template_assignments
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_assignment();

CREATE FUNCTION plan_draft_validate_approval()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT plan_draft_validate_current_revision_reference(
            NEW.plan_draft_id, NEW.draft_revision_id, NEW.revision_content_hash) THEN
        RAISE EXCEPTION 'approval must reference current revision and content hash';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM project_members
        WHERE project_id = NEW.project_id AND id = NEW.actor_member_id AND is_active
    ) THEN
        RAISE EXCEPTION 'approval actor must be active';
    END IF;
    IF NEW.approval_scope = 'project' AND NOT EXISTS (
        SELECT 1 FROM projects
        WHERE id = NEW.project_id AND owner_member_id = NEW.actor_member_id
    ) THEN
        RAISE EXCEPTION 'project approval actor must be project owner';
    END IF;
    IF NEW.approval_scope = 'part' AND NOT EXISTS (
        SELECT 1 FROM project_part_memberships
        WHERE project_id = NEW.project_id
          AND part_id = NEW.part_id
          AND member_id = NEW.actor_member_id
          AND part_role = 'part_lead'
    ) THEN
        RAISE EXCEPTION 'part approval actor must be part lead';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_draft_approvals_validate
BEFORE INSERT ON plan_draft_approvals
FOR EACH ROW EXECUTE FUNCTION plan_draft_validate_approval();
