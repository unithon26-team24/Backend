package com.uniton.backend.planning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanDraftRevision(
        UUID draftId,
        UUID revisionId,
        UUID projectId,
        int revisionNumber,
        String contentHash,
        UUID actorMemberId,
        List<SuccessCriterion> successCriteria,
        List<MemberResponsibility> memberResponsibilities,
        List<Assumption> assumptions,
        List<Milestone> milestones,
        List<MilestoneCriterion> milestoneCriteria,
        List<MeetingBlueprint> meetingBlueprints,
        List<ActionTemplate> actionTemplates,
        List<TemplateAssignment> templateAssignments,
        List<LabelConfirmation> labelConfirmations) {

    public record SuccessCriterion(
            UUID id,
            String clientKey,
            String statement,
            String verificationMethod,
            String shortRationale,
            String confirmationStatus) {}

    public record MemberResponsibility(
            UUID id,
            UUID memberId,
            List<String> labels,
            String shortRationale,
            String confirmationStatus) {}

    public record Assumption(
            UUID id,
            String statement,
            String whyItMatters,
            boolean needsOwnerConfirmation,
            String confirmationStatus) {}

    public record Milestone(
            UUID id,
            String clientKey,
            int position,
            String title,
            Instant targetAt,
            String deliverable,
            String shortRationale) {}

    public record MilestoneCriterion(UUID milestoneId, UUID successCriterionId) {}

    public record MeetingBlueprint(
            UUID id,
            UUID milestoneId,
            UUID partId,
            String meetingType,
            String checkpointPhase,
            String targetWindow,
            int durationSeconds,
            String triggerRulesJson,
            String purpose,
            List<String> mustDecideItems,
            String exitGate,
            UUID meetingOwnerMemberId,
            List<AgendaItem> agendaItems) {}

    public record AgendaItem(UUID id, int position, String title, int allocatedSeconds) {}

    public record ActionTemplate(
            UUID id,
            String clientKey,
            UUID milestoneId,
            String title,
            String description,
            UUID candidateMemberId,
            Instant dueAt,
            String definitionOfDone,
            List<String> requiredLabels,
            String shortRationale) {

        public boolean needsOwnerSelection() {
            return candidateMemberId == null;
        }
    }

    public record TemplateAssignment(
            UUID id, String templateClientKey, UUID selectedMemberId, UUID selectedByMemberId) {}

    public record LabelConfirmation(
            UUID id,
            UUID memberId,
            List<String> labels,
            String decision,
            UUID confirmedByMemberId) {}
}
