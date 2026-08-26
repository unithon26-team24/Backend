package com.uniton.backend.planning;

import com.uniton.backend.persistence.ProjectRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class PlanDraftTestFixture {

    static final String HASH_ONE = "1".repeat(64);
    static final String HASH_TWO = "2".repeat(64);

    private PlanDraftTestFixture() {}

    static Context createProject(Connection connection) throws SQLException {
        UUID projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        ProjectRepository repository = new ProjectRepository(connection);
        repository.createProject(new ProjectRepository.ProjectSetup(
                projectId,
                "Draft project",
                "Ship validated result",
                Instant.parse("2026-09-30T00:00:00Z"),
                "Asia/Seoul",
                ownerId,
                "U-OWNER",
                "Owner",
                List.of("기획")));
        repository.addMember(projectId, leadId, "U-LEAD", "Lead", List.of("백엔드"));
        repository.addMember(projectId, memberId, "U-MEMBER", "Member", List.of("QA"));
        repository.addPart(projectId, partId, "Backend", leadId);
        return new Context(projectId, ownerId, leadId, memberId, partId, UUID.randomUUID());
    }

    static PlanDraftRevision validRevision(Context context) {
        return validRevision(context, UUID.randomUUID(), 1, HASH_ONE);
    }

    static PlanDraftRevision validRevision(
            Context context, UUID revisionId, int revisionNumber, String contentHash) {
        UUID criterionId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        String templateKey = "template-1";
        return new PlanDraftRevision(
                context.draftId(),
                revisionId,
                context.projectId(),
                revisionNumber,
                contentHash,
                context.ownerId(),
                List.of(new PlanDraftRevision.SuccessCriterion(
                        criterionId,
                        "criterion-1",
                        "Demo passes",
                        "Run acceptance scenario",
                        "Defines completion",
                        "accepted")),
                List.of(new PlanDraftRevision.MemberResponsibility(
                        UUID.randomUUID(),
                        context.leadId(),
                        List.of("백엔드"),
                        "Owns implementation",
                        "accepted")),
                List.of(new PlanDraftRevision.Assumption(
                        UUID.randomUUID(),
                        "PostgreSQL is available",
                        "Persistence needs it",
                        false,
                        "accepted")),
                List.of(new PlanDraftRevision.Milestone(
                        milestoneId,
                        "milestone-1",
                        1,
                        "MVP",
                        Instant.parse("2026-09-15T00:00:00Z"),
                        "Working demo",
                        "First bounded delivery")),
                List.of(new PlanDraftRevision.MilestoneCriterion(milestoneId, criterionId)),
                List.of(new PlanDraftRevision.MeetingBlueprint(
                        UUID.randomUUID(),
                        milestoneId,
                        context.partId(),
                        "milestone_checkpoint",
                        "readiness",
                        "Before target",
                        1800,
                        "{}",
                        "Confirm readiness",
                        List.of("Scope"),
                        "Decision recorded",
                        context.leadId(),
                        List.of(
                                new PlanDraftRevision.AgendaItem(UUID.randomUUID(), 1, "Review", 900),
                                new PlanDraftRevision.AgendaItem(UUID.randomUUID(), 2, "Decide", 900)))),
                List.of(new PlanDraftRevision.ActionTemplate(
                        UUID.randomUUID(),
                        templateKey,
                        milestoneId,
                        "Implement migration",
                        "Create hierarchy",
                        context.leadId(),
                        Instant.parse("2026-09-14T00:00:00Z"),
                        "All checks pass",
                        List.of("백엔드"),
                        "Required for persistence")),
                List.of(new PlanDraftRevision.TemplateAssignment(
                        UUID.randomUUID(), templateKey, context.leadId(), context.ownerId())),
                List.of(new PlanDraftRevision.LabelConfirmation(
                        UUID.randomUUID(),
                        context.leadId(),
                        List.of("백엔드"),
                        "accepted",
                        context.ownerId())));
    }

    static PlanDraftRevision withBlueprints(
            PlanDraftRevision source, List<PlanDraftRevision.MeetingBlueprint> blueprints) {
        return copy(source, source.milestoneCriteria(), blueprints, source.actionTemplates(), source.templateAssignments());
    }

    static PlanDraftRevision withCoverage(
            PlanDraftRevision source, List<PlanDraftRevision.MilestoneCriterion> coverage) {
        return copy(source, coverage, source.meetingBlueprints(), source.actionTemplates(), source.templateAssignments());
    }

    static PlanDraftRevision withTemplates(
            PlanDraftRevision source,
            List<PlanDraftRevision.ActionTemplate> templates,
            List<PlanDraftRevision.TemplateAssignment> assignments) {
        return copy(source, source.milestoneCriteria(), source.meetingBlueprints(), templates, assignments);
    }

    private static PlanDraftRevision copy(
            PlanDraftRevision source,
            List<PlanDraftRevision.MilestoneCriterion> coverage,
            List<PlanDraftRevision.MeetingBlueprint> blueprints,
            List<PlanDraftRevision.ActionTemplate> templates,
            List<PlanDraftRevision.TemplateAssignment> assignments) {
        return new PlanDraftRevision(
                source.draftId(), source.revisionId(), source.projectId(), source.revisionNumber(),
                source.contentHash(), source.actorMemberId(), source.successCriteria(),
                source.memberResponsibilities(), source.assumptions(), source.milestones(), coverage,
                blueprints, templates, assignments, source.labelConfirmations());
    }

    record Context(
            UUID projectId, UUID ownerId, UUID leadId, UUID memberId, UUID partId, UUID draftId) {}
}
