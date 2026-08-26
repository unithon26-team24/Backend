package com.uniton.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniton.backend.persistence.PostgresIntegrationSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemplateOwnerSelectionMigrationTest extends PostgresIntegrationSupport {

    private Connection database;
    private PlanDraftRepository repository;
    private PlanDraftTestFixture.Context context;

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        cleanAndMigrate();
        database = connection();
        repository = new PlanDraftRepository(database);
        context = PlanDraftTestFixture.createProject(database);
    }

    @Test
    void persistsDerivedUnresolvedPairWithoutModelSelectionState() throws SQLException {
        // Given
        PlanDraftRevision valid = PlanDraftTestFixture.validRevision(context);
        var source = valid.actionTemplates().getFirst();
        var unresolved = template(source, source.clientKey(), null);
        PlanDraftRevision revision = PlanDraftTestFixture.withTemplates(
                valid, List.of(unresolved), List.of());

        // When
        repository.createRevision(revision);

        // Then
        assertThat(templatePair()).containsExactly(null, true);
        assertThat(List.of(PlanDraftRevision.ActionTemplate.class.getRecordComponents())
                        .stream().map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("needsOwnerSelection");
        System.out.println("DATA_SURFACE candidate=null needs_owner_selection=true model_flag_surface=false");
    }

    @Test
    void persistsResolvedPairWithRevisionScopedSelectionProvenance() throws SQLException {
        // Given
        PlanDraftRevision revision = PlanDraftTestFixture.validRevision(context);

        // When
        repository.createRevision(revision);

        // Then
        assertThat(templatePair()).containsExactly(context.leadId(), false);
        assertThat(assignmentBinding()).containsExactly(
                revision.revisionId(), revision.contentHash(), "template-1", context.ownerId(), context.leadId());
        System.out.println("DATA_SURFACE selection_key=template-1 revision_hash_bound=true provenance_rows=1");
    }

    @Test
    void rejectsNullFalseAndNonnullTrueSelectionPairsWithoutPartialRevision() throws SQLException {
        // Given
        UUID activeCandidate = context.leadId();

        // When / Then
        assertThatThrownBy(() -> insertInvalidPair(null, false)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertInvalidPair(activeCandidate, true)).isInstanceOf(SQLException.class);
        assertThat(revisionCount()).isZero();
        System.out.println("DATA_SURFACE null_false=rejected nonnull_true=rejected leaked_revisions=0");
    }

    @Test
    void rejectsMissingDuplicateAndUnknownTemplateKeysAtomically() throws SQLException {
        // Given
        PlanDraftRevision missingBase = PlanDraftTestFixture.validRevision(context);
        var source = missingBase.actionTemplates().getFirst();
        PlanDraftRevision missing = PlanDraftTestFixture.withTemplates(
                missingBase, List.of(template(source, "", context.leadId())), List.of());
        PlanDraftRevision duplicateBase = PlanDraftTestFixture.validRevision(context);
        var first = duplicateBase.actionTemplates().getFirst();
        var duplicate = template(new PlanDraftRevision.ActionTemplate(
                UUID.randomUUID(), first.clientKey(), first.milestoneId(), first.title(), first.description(),
                first.candidateMemberId(), first.dueAt(), first.definitionOfDone(), first.requiredLabels(),
                first.shortRationale()), first.clientKey(), context.leadId());
        PlanDraftRevision duplicates = PlanDraftTestFixture.withTemplates(
                duplicateBase, List.of(first, duplicate), List.of());
        PlanDraftRevision unknownBase = PlanDraftTestFixture.validRevision(context);
        PlanDraftRevision unknown = PlanDraftTestFixture.withTemplates(
                unknownBase, unknownBase.actionTemplates(), List.of(new PlanDraftRevision.TemplateAssignment(
                        UUID.randomUUID(), "unknown-key", context.leadId(), context.ownerId())));

        // When / Then
        assertRejected(missing);
        assertRejected(duplicates);
        assertRejected(unknown);
    }

    @Test
    void rejectsDuplicateAndStaleSelectionProvenanceAtomically() throws SQLException {
        // Given
        PlanDraftRevision duplicateBase = PlanDraftTestFixture.validRevision(context);
        var assignment = duplicateBase.templateAssignments().getFirst();
        PlanDraftRevision duplicate = PlanDraftTestFixture.withTemplates(
                duplicateBase,
                duplicateBase.actionTemplates(),
                List.of(assignment, new PlanDraftRevision.TemplateAssignment(
                        UUID.randomUUID(), assignment.templateClientKey(), context.leadId(), context.ownerId())));

        // When / Then
        assertRejected(duplicate);
        PlanDraftRevision firstBase = PlanDraftTestFixture.validRevision(context);
        PlanDraftRevision first = PlanDraftTestFixture.withTemplates(
                firstBase, firstBase.actionTemplates(), List.of());
        repository.createRevision(first);
        PlanDraftRevision later = PlanDraftTestFixture.validRevision(
                context, UUID.randomUUID(), 2, PlanDraftTestFixture.HASH_TWO);
        repository.createRevision(later);
        assertThatThrownBy(() -> insertStaleAssignment(first)).isInstanceOf(SQLException.class);
    }

    private PlanDraftRevision.ActionTemplate template(
            PlanDraftRevision.ActionTemplate source, String key, UUID candidateId) {
        return new PlanDraftRevision.ActionTemplate(
                source.id(), key, source.milestoneId(), source.title(), source.description(), candidateId,
                source.dueAt(), source.definitionOfDone(), source.requiredLabels(), source.shortRationale());
    }

    private void assertRejected(PlanDraftRevision revision) throws SQLException {
        assertThatThrownBy(() -> repository.createRevision(revision)).isInstanceOf(SQLException.class);
        assertThat(revisionCount()).isZero();
    }

    private void insertStaleAssignment(PlanDraftRevision revision) throws SQLException {
        try (var statement = database.prepareStatement("""
                INSERT INTO plan_draft_template_assignments
                  (id, plan_draft_id, project_id, draft_revision_id, revision_content_hash,
                   template_client_key, selected_member_id, selected_by_member_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, revision.draftId());
            statement.setObject(3, revision.projectId());
            statement.setObject(4, revision.revisionId());
            statement.setString(5, revision.contentHash());
            statement.setString(6, revision.actionTemplates().getFirst().clientKey());
            statement.setObject(7, context.leadId());
            statement.setObject(8, context.ownerId());
            statement.executeUpdate();
        }
    }

    private void insertInvalidPair(UUID candidateId, boolean needsSelection) throws SQLException {
        UUID draftId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        database.setAutoCommit(false);
        try (var draft = database.prepareStatement(
                        "INSERT INTO plan_drafts (id, project_id) VALUES (?, ?)");
                var revision = database.prepareStatement("""
                        INSERT INTO draft_revisions
                          (id, plan_draft_id, project_id, revision_number, content_hash, created_by_member_id)
                        VALUES (?, ?, ?, 1, ?, ?)
                        """);
                var milestone = database.prepareStatement("""
                        INSERT INTO plan_draft_milestones
                          (id, project_id, draft_revision_id, client_key, position, title,
                           target_at, deliverable, short_rationale)
                        VALUES (?, ?, ?, 'milestone-raw', 1, 'Raw', '2026-09-15T00:00:00Z',
                                'Deliverable', 'Rationale')
                        """);
                var template = database.prepareStatement("""
                        INSERT INTO plan_draft_action_templates
                          (id, project_id, draft_revision_id, client_key, milestone_id, title,
                           description, candidate_member_id, needs_owner_selection, due_at,
                           definition_of_done, required_labels, short_rationale)
                        VALUES (?, ?, ?, 'template-raw', ?, 'Task', 'Description', ?, ?,
                                '2026-09-14T00:00:00Z', 'Done', ARRAY['백엔드'], 'Rationale')
                        """)) {
            draft.setObject(1, draftId);
            draft.setObject(2, context.projectId());
            draft.executeUpdate();
            revision.setObject(1, revisionId);
            revision.setObject(2, draftId);
            revision.setObject(3, context.projectId());
            revision.setString(4, PlanDraftTestFixture.HASH_ONE);
            revision.setObject(5, context.ownerId());
            revision.executeUpdate();
            milestone.setObject(1, milestoneId);
            milestone.setObject(2, context.projectId());
            milestone.setObject(3, revisionId);
            milestone.executeUpdate();
            template.setObject(1, UUID.randomUUID());
            template.setObject(2, context.projectId());
            template.setObject(3, revisionId);
            template.setObject(4, milestoneId);
            template.setObject(5, candidateId);
            template.setBoolean(6, needsSelection);
            template.executeUpdate();
        } finally {
            database.rollback();
            database.setAutoCommit(true);
        }
    }

    private List<Object> templatePair() throws SQLException {
        try (var rows = database.createStatement().executeQuery("""
                SELECT candidate_member_id, needs_owner_selection FROM plan_draft_action_templates
                """)) {
            rows.next();
            return java.util.Arrays.asList(rows.getObject(1), rows.getBoolean(2));
        }
    }

    private List<Object> assignmentBinding() throws SQLException {
        try (var rows = database.createStatement().executeQuery("""
                SELECT draft_revision_id, revision_content_hash, template_client_key,
                       selected_by_member_id, selected_member_id
                FROM plan_draft_template_assignments
                """)) {
            rows.next();
            return List.of(rows.getObject(1), rows.getString(2), rows.getString(3), rows.getObject(4), rows.getObject(5));
        }
    }

    private int revisionCount() throws SQLException {
        try (var rows = database.createStatement().executeQuery("SELECT count(*) FROM draft_revisions")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
