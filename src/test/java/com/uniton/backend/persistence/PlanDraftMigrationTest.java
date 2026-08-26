package com.uniton.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanDraftMigrationTest extends PostgresIntegrationSupport {

    @BeforeEach
    void migrateFreshDatabase() {
        cleanAndMigrate();
    }

    @Test
    void createsEditableDraftAndImmutableRevisionTables() throws SQLException {
        // Given
        String[] requiredTables = {
            "plan_drafts",
            "draft_revisions",
            "plan_draft_success_criteria",
            "plan_draft_member_responsibilities",
            "plan_draft_assumptions",
            "plan_draft_milestones",
            "plan_draft_milestone_criteria",
            "plan_draft_meeting_blueprints",
            "plan_draft_agenda_items",
            "plan_draft_action_templates",
            "plan_draft_template_assignments",
            "plan_draft_responsibility_label_confirmations",
            "plan_draft_approvals",
            "plan_draft_revision_audit_events"
        };

        // When / Then
        try (var connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = ANY (?)
                        """)) {
            statement.setArray(1, connection.createArrayOf("text", requiredTables));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(requiredTables.length);
            }
        }
    }
}
