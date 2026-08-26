package com.uniton.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

public final class PlanDraftApprovalRepository {

    private final Connection connection;

    public PlanDraftApprovalRepository(Connection connection) {
        this.connection = connection;
    }

    public void submit(Approval approval) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan_draft_approvals
                  (id, plan_draft_id, project_id, draft_revision_id, revision_content_hash,
                   approval_scope, part_id, actor_member_id, decision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, approval.id());
            statement.setObject(2, approval.draftId());
            statement.setObject(3, approval.projectId());
            statement.setObject(4, approval.revisionId());
            statement.setString(5, approval.contentHash());
            statement.setString(6, approval.scope());
            statement.setObject(7, approval.partId());
            statement.setObject(8, approval.actorMemberId());
            statement.setString(9, approval.decision());
            statement.executeUpdate();
        }
    }

    public boolean isCurrentRevisionEligible(UUID draftId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM plan_drafts draft
                    JOIN draft_revisions revision ON revision.id = draft.current_revision_id
                    JOIN projects project ON project.id = draft.project_id
                    WHERE draft.id = ?
                      AND EXISTS (
                          SELECT 1 FROM plan_draft_approvals approval
                          WHERE approval.draft_revision_id = revision.id
                            AND approval.approval_scope = 'project'
                            AND approval.actor_member_id = project.owner_member_id
                            AND approval.decision = 'approved'
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM project_parts part
                          WHERE part.project_id = draft.project_id
                            AND NOT EXISTS (
                                SELECT 1 FROM plan_draft_approvals approval
                                WHERE approval.draft_revision_id = revision.id
                                  AND approval.approval_scope = 'part'
                                  AND approval.part_id = part.id
                                  AND approval.decision = 'approved'
                            )
                      )
                )
                """)) {
            statement.setObject(1, draftId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    public record Approval(
            UUID id,
            UUID draftId,
            UUID projectId,
            UUID revisionId,
            String contentHash,
            String scope,
            UUID partId,
            UUID actorMemberId,
            String decision) {}
}
