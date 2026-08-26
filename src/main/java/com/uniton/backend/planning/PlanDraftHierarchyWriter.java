package com.uniton.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

final class PlanDraftHierarchyWriter {

    private final Connection connection;

    PlanDraftHierarchyWriter(Connection connection) {
        this.connection = connection;
    }

    void write(PlanDraftRevision revision) throws SQLException {
        insertDraftAndRevision(revision);
        insertCriteria(revision);
        insertResponsibilities(revision);
        insertAssumptions(revision);
        insertMilestones(revision);
        insertCoverage(revision);
    }

    private void insertDraftAndRevision(PlanDraftRevision revision) throws SQLException {
        try (var draft = connection.prepareStatement("""
                        INSERT INTO plan_drafts (id, project_id) VALUES (?, ?)
                        ON CONFLICT (project_id) DO NOTHING
                        """);
                var row = connection.prepareStatement("""
                        INSERT INTO draft_revisions
                          (id, plan_draft_id, project_id, revision_number, content_hash, created_by_member_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            draft.setObject(1, revision.draftId());
            draft.setObject(2, revision.projectId());
            draft.executeUpdate();
            lockDraft(revision.draftId());
            row.setObject(1, revision.revisionId());
            row.setObject(2, revision.draftId());
            row.setObject(3, revision.projectId());
            row.setInt(4, revision.revisionNumber());
            row.setString(5, revision.contentHash());
            row.setObject(6, revision.actorMemberId());
            row.executeUpdate();
        }
    }

    private void lockDraft(UUID draftId) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT id FROM plan_drafts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, draftId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("plan draft does not exist");
                }
            }
        }
    }

    private void insertCriteria(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_success_criteria
                  (id, project_id, draft_revision_id, client_key, statement, verification_method,
                   short_rationale, confirmation_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var criterion : revision.successCriteria()) {
                statement.setObject(1, criterion.id());
                statement.setObject(2, revision.projectId());
                statement.setObject(3, revision.revisionId());
                statement.setString(4, criterion.clientKey());
                statement.setString(5, criterion.statement());
                statement.setString(6, criterion.verificationMethod());
                statement.setString(7, criterion.shortRationale());
                statement.setString(8, criterion.confirmationStatus());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertResponsibilities(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_member_responsibilities
                  (id, project_id, draft_revision_id, member_id, labels, short_rationale, confirmation_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var responsibility : revision.memberResponsibilities()) {
                statement.setObject(1, responsibility.id());
                statement.setObject(2, revision.projectId());
                statement.setObject(3, revision.revisionId());
                statement.setObject(4, responsibility.memberId());
                statement.setArray(5, textArray(responsibility.labels()));
                statement.setString(6, responsibility.shortRationale());
                statement.setString(7, responsibility.confirmationStatus());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAssumptions(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_assumptions
                  (id, project_id, draft_revision_id, statement, why_it_matters,
                   needs_owner_confirmation, confirmation_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var assumption : revision.assumptions()) {
                statement.setObject(1, assumption.id());
                statement.setObject(2, revision.projectId());
                statement.setObject(3, revision.revisionId());
                statement.setString(4, assumption.statement());
                statement.setString(5, assumption.whyItMatters());
                statement.setBoolean(6, assumption.needsOwnerConfirmation());
                statement.setString(7, assumption.confirmationStatus());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertMilestones(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_milestones
                  (id, project_id, draft_revision_id, client_key, position, title,
                   target_at, deliverable, short_rationale)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var milestone : revision.milestones()) {
                statement.setObject(1, milestone.id());
                statement.setObject(2, revision.projectId());
                statement.setObject(3, revision.revisionId());
                statement.setString(4, milestone.clientKey());
                statement.setInt(5, milestone.position());
                statement.setString(6, milestone.title());
                statement.setTimestamp(7, Timestamp.from(milestone.targetAt()));
                statement.setString(8, milestone.deliverable());
                statement.setString(9, milestone.shortRationale());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCoverage(PlanDraftRevision revision) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_milestone_criteria
                  (draft_revision_id, milestone_id, success_criterion_id)
                VALUES (?, ?, ?)
                """)) {
            for (var coverage : revision.milestoneCriteria()) {
                statement.setObject(1, revision.revisionId());
                statement.setObject(2, coverage.milestoneId());
                statement.setObject(3, coverage.successCriterionId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private java.sql.Array textArray(java.util.List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }
}
