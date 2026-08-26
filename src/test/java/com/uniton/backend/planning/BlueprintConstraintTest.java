package com.uniton.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniton.backend.persistence.PostgresIntegrationSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlueprintConstraintTest extends PostgresIntegrationSupport {

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
    void persistsDefaultBoundedBlueprintWithExactOrderedAgenda() throws SQLException {
        // Given
        PlanDraftRevision revision = PlanDraftTestFixture.validRevision(context);

        // When
        repository.createRevision(revision);

        // Then
        assertThat(count("draft_revisions")).isEqualTo(1);
        assertThat(count("plan_draft_agenda_items")).isEqualTo(2);
        assertThat(count("plan_draft_revision_audit_events")).isEqualTo(1);
        System.out.println("DATA_SURFACE blueprint_duration=1800 agenda_count=2 agenda_total=1800 revision_rows=1");
    }

    @Test
    void rejectsMissingMeetingOwnerAtomically() throws SQLException {
        // Given
        PlanDraftRevision valid = PlanDraftTestFixture.validRevision(context);
        var blueprint = blueprint(valid, 1800, valid.meetingBlueprints().getFirst().agendaItems(), null);

        // When / Then
        assertRejected(PlanDraftTestFixture.withBlueprints(valid, List.of(blueprint)));
    }

    @Test
    void rejectsDurationOutsideBoundsAtomically() throws SQLException {
        // Given
        PlanDraftRevision below = PlanDraftTestFixture.validRevision(context);
        PlanDraftRevision above = PlanDraftTestFixture.validRevision(context);

        // When / Then
        assertRejected(PlanDraftTestFixture.withBlueprints(
                below, List.of(blueprint(below, 899, agenda(899), context.leadId()))));
        assertRejected(PlanDraftTestFixture.withBlueprints(
                above, List.of(blueprint(above, 5401, agenda(5401), context.leadId()))));
    }

    @Test
    void rejectsEmptyNonpositiveOutOfOrderAndMismatchedAgendaAtomically() throws SQLException {
        // Given
        List<List<PlanDraftRevision.AgendaItem>> invalidAgendas = List.of(
                List.of(),
                List.of(new PlanDraftRevision.AgendaItem(java.util.UUID.randomUUID(), 1, "Invalid", 0)),
                List.of(
                        new PlanDraftRevision.AgendaItem(java.util.UUID.randomUUID(), 1, "First", 900),
                        new PlanDraftRevision.AgendaItem(java.util.UUID.randomUUID(), 3, "Third", 900)),
                List.of(new PlanDraftRevision.AgendaItem(java.util.UUID.randomUUID(), 1, "Short", 900)));

        // When / Then
        for (var invalidAgenda : invalidAgendas) {
            PlanDraftRevision revision = PlanDraftTestFixture.validRevision(context);
            assertRejected(PlanDraftTestFixture.withBlueprints(
                    revision, List.of(blueprint(revision, 1800, invalidAgenda, context.leadId()))));
        }
    }

    @Test
    void rejectsUncoveredCriterionAndLateTaskAtomically() throws SQLException {
        // Given
        PlanDraftRevision uncovered = PlanDraftTestFixture.validRevision(context);
        PlanDraftRevision late = PlanDraftTestFixture.validRevision(context);
        var template = late.actionTemplates().getFirst();
        var lateTemplate = new PlanDraftRevision.ActionTemplate(
                template.id(), template.clientKey(), template.milestoneId(), template.title(),
                template.description(), template.candidateMemberId(), Instant.parse("2026-09-16T00:00:00Z"),
                template.definitionOfDone(), template.requiredLabels(), template.shortRationale());

        // When / Then
        assertRejected(PlanDraftTestFixture.withCoverage(uncovered, List.of()));
        assertRejected(PlanDraftTestFixture.withTemplates(late, List.of(lateTemplate), late.templateAssignments()));
    }

    private PlanDraftRevision.MeetingBlueprint blueprint(
            PlanDraftRevision revision,
            int duration,
            List<PlanDraftRevision.AgendaItem> agendaItems,
            java.util.UUID ownerId) {
        var source = revision.meetingBlueprints().getFirst();
        return new PlanDraftRevision.MeetingBlueprint(
                source.id(), source.milestoneId(), source.partId(), source.meetingType(),
                source.checkpointPhase(), source.targetWindow(), duration, source.triggerRulesJson(),
                source.purpose(), source.mustDecideItems(), source.exitGate(), ownerId, agendaItems);
    }

    private List<PlanDraftRevision.AgendaItem> agenda(int seconds) {
        return List.of(new PlanDraftRevision.AgendaItem(java.util.UUID.randomUUID(), 1, "Agenda", seconds));
    }

    private void assertRejected(PlanDraftRevision revision) throws SQLException {
        assertThatThrownBy(() -> repository.createRevision(revision)).isInstanceOf(SQLException.class);
        assertThat(count("draft_revisions")).isZero();
    }

    private int count(String table) throws SQLException {
        try (var statement = database.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
