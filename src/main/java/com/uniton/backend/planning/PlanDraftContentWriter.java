package com.uniton.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

final class PlanDraftContentWriter {

    private final Connection connection;

    PlanDraftContentWriter(Connection connection) {
        this.connection = connection;
    }

    void write(PlanDraftRevision revision) throws SQLException {
        insertBlueprints(revision);
        insertTemplates(revision);
        setCurrentRevision(revision);
        insertAssignments(revision);
        insertLabelConfirmations(revision);
        insertAudit(revision);
        sealRevision(revision.revisionId());
    }

    private void insertBlueprints(PlanDraftRevision revision) throws SQLException {
        try (var blueprint = connection.prepareStatement("""
                        INSERT INTO plan_draft_meeting_blueprints
                          (id, project_id, draft_revision_id, milestone_id, part_id, meeting_type,
                           checkpoint_phase, target_window, duration_seconds, trigger_rules, purpose,
                           must_decide_items, exit_gate, meeting_owner_member_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                        """);
                var agenda = connection.prepareStatement("""
                        INSERT INTO plan_draft_agenda_items
                          (id, draft_revision_id, meeting_blueprint_id, position, title, allocated_seconds)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            for (var item : revision.meetingBlueprints()) {
                blueprint.setObject(1, item.id());
                blueprint.setObject(2, revision.projectId());
                blueprint.setObject(3, revision.revisionId());
                blueprint.setObject(4, item.milestoneId());
                blueprint.setObject(5, item.partId());
                blueprint.setString(6, item.meetingType());
                blueprint.setString(7, item.checkpointPhase());
                blueprint.setString(8, item.targetWindow());
                blueprint.setInt(9, item.durationSeconds());
                blueprint.setString(10, item.triggerRulesJson());
                blueprint.setString(11, item.purpose());
                blueprint.setArray(12, textArray(item.mustDecideItems()));
                blueprint.setString(13, item.exitGate());
                blueprint.setObject(14, item.meetingOwnerMemberId());
                blueprint.executeUpdate();
                for (var agendaItem : item.agendaItems()) {
                    agenda.setObject(1, agendaItem.id());
                    agenda.setObject(2, revision.revisionId());
                    agenda.setObject(3, item.id());
                    agenda.setInt(4, agendaItem.position());
                    agenda.setString(5, agendaItem.title());
                    agenda.setInt(6, agendaItem.allocatedSeconds());
                    agenda.addBatch();
                }
                agenda.executeBatch();
            }
        }
    }

    private void insertTemplates(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_action_templates
                  (id, project_id, draft_revision_id, client_key, milestone_id, title, description,
                   candidate_member_id, needs_owner_selection, due_at, definition_of_done,
                   required_labels, short_rationale)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var template : revision.actionTemplates()) {
                statement.setObject(1, template.id());
                statement.setObject(2, revision.projectId());
                statement.setObject(3, revision.revisionId());
                statement.setString(4, template.clientKey());
                statement.setObject(5, template.milestoneId());
                statement.setString(6, template.title());
                statement.setString(7, template.description());
                statement.setObject(8, template.candidateMemberId());
                statement.setBoolean(9, template.needsOwnerSelection());
                statement.setTimestamp(10, Timestamp.from(template.dueAt()));
                statement.setString(11, template.definitionOfDone());
                statement.setArray(12, textArray(template.requiredLabels()));
                statement.setString(13, template.shortRationale());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void setCurrentRevision(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE plan_drafts
                SET current_revision_id = ?, status = 'draft', updated_at = clock_timestamp()
                WHERE id = ? AND project_id = ?
                """)) {
            statement.setObject(1, revision.revisionId());
            statement.setObject(2, revision.draftId());
            statement.setObject(3, revision.projectId());
            statement.executeUpdate();
        }
    }

    private void insertAssignments(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_template_assignments
                  (id, plan_draft_id, project_id, draft_revision_id, revision_content_hash,
                   template_client_key, selected_member_id, selected_by_member_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var assignment : revision.templateAssignments()) {
                statement.setObject(1, assignment.id());
                statement.setObject(2, revision.draftId());
                statement.setObject(3, revision.projectId());
                statement.setObject(4, revision.revisionId());
                statement.setString(5, revision.contentHash());
                statement.setString(6, assignment.templateClientKey());
                statement.setObject(7, assignment.selectedMemberId());
                statement.setObject(8, assignment.selectedByMemberId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertLabelConfirmations(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_responsibility_label_confirmations
                  (id, plan_draft_id, project_id, draft_revision_id, revision_content_hash,
                   member_id, labels, decision, confirmed_by_member_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var confirmation : revision.labelConfirmations()) {
                statement.setObject(1, confirmation.id());
                statement.setObject(2, revision.draftId());
                statement.setObject(3, revision.projectId());
                statement.setObject(4, revision.revisionId());
                statement.setString(5, revision.contentHash());
                statement.setObject(6, confirmation.memberId());
                statement.setArray(7, textArray(confirmation.labels()));
                statement.setString(8, confirmation.decision());
                statement.setObject(9, confirmation.confirmedByMemberId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAudit(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_revision_audit_events
                  (id, plan_draft_id, project_id, draft_revision_id, revision_content_hash,
                   actor_member_id, event_type)
                VALUES (?, ?, ?, ?, ?, ?, 'revision_applied')
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, revision.draftId());
            statement.setObject(3, revision.projectId());
            statement.setObject(4, revision.revisionId());
            statement.setString(5, revision.contentHash());
            statement.setObject(6, revision.actorMemberId());
            statement.executeUpdate();
        }
    }

    private void sealRevision(UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE draft_revisions SET sealed_at = clock_timestamp() WHERE id = ?")) {
            statement.setObject(1, revisionId);
            statement.executeUpdate();
        }
    }

    private java.sql.Array textArray(java.util.List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }
}
