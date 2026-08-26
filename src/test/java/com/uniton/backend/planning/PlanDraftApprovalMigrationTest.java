package com.uniton.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniton.backend.persistence.PostgresIntegrationSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanDraftApprovalMigrationTest extends PostgresIntegrationSupport {

    private Connection database;
    private PlanDraftRepository drafts;
    private PlanDraftApprovalRepository approvals;
    private PlanDraftTestFixture.Context context;
    private PlanDraftRevision revision;

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        cleanAndMigrate();
        database = connection();
        drafts = new PlanDraftRepository(database);
        approvals = new PlanDraftApprovalRepository(database);
        context = PlanDraftTestFixture.createProject(database);
        revision = PlanDraftTestFixture.validRevision(context);
        drafts.createRevision(revision);
    }

    @Test
    void currentOwnerAndPartLeadApprovalsMakeRevisionEligible() throws SQLException {
        // Given
        var ownerApproval = approval("project", null, context.ownerId(), revision);
        var partApproval = approval("part", context.partId(), context.leadId(), revision);

        // When
        approvals.submit(ownerApproval);
        approvals.submit(partApproval);

        // Then
        assertThat(approvals.isCurrentRevisionEligible(context.draftId())).isTrue();
        System.out.println("DATA_SURFACE current_revision_approvals=owner,part_lead eligible=true");
    }

    @Test
    void rejectsNonLeadAndDuplicateApprovalScope() throws SQLException {
        // Given
        var nonLead = approval("part", context.partId(), context.memberId(), revision);
        var owner = approval("project", null, context.ownerId(), revision);

        // When / Then
        assertThatThrownBy(() -> approvals.submit(nonLead)).isInstanceOf(SQLException.class);
        approvals.submit(owner);
        assertThatThrownBy(() -> approvals.submit(approval(
                        "project", null, context.ownerId(), revision)))
                .isInstanceOf(SQLException.class);
        assertThat(approvalCount()).isEqualTo(1);
    }

    @Test
    void laterRevisionInvalidatesPriorApprovalAndRejectsStaleSubmission() throws SQLException {
        // Given
        approvals.submit(approval("project", null, context.ownerId(), revision));
        PlanDraftRevision later = PlanDraftTestFixture.validRevision(
                context, UUID.randomUUID(), 2, PlanDraftTestFixture.HASH_TWO);

        // When
        drafts.createRevision(later);

        // Then
        assertThat(approvals.isCurrentRevisionEligible(context.draftId())).isFalse();
        assertThatThrownBy(() -> approvals.submit(approval(
                        "part", context.partId(), context.leadId(), revision)))
                .isInstanceOf(SQLException.class);
        assertThat(approvalCount()).isEqualTo(1);
        System.out.println("DATA_SURFACE stale_revision_approval=rejected inherited_eligibility=false");
    }

    @Test
    void approvalHistoryIsAppendOnly() throws SQLException {
        // Given
        approvals.submit(approval("project", null, context.ownerId(), revision));

        // When / Then
        assertThatThrownBy(() -> database.createStatement()
                        .executeUpdate("UPDATE plan_draft_approvals SET decision = 'rejected'"))
                .isInstanceOf(SQLException.class);
        assertThat(approvalCount()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateScopeProducesOneDurableApproval() throws Exception {
        // Given
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitAfter(start));
            var second = executor.submit(() -> submitAfter(start));

            // When
            start.countDown();

            // Then
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(approvalCount()).isEqualTo(1);
        System.out.println("DATA_SURFACE concurrent_approval_writers=2 durable_scope_records=1");
    }

    @Test
    void concurrentApplyRejectsApprovalForRevisionItSupersedes() throws Exception {
        // Given
        PlanDraftRevision later = PlanDraftTestFixture.validRevision(
                context, UUID.randomUUID(), 2, PlanDraftTestFixture.HASH_TWO);
        try (Connection applyConnection = connection();
                var executor = Executors.newSingleThreadExecutor()) {
            applyConnection.setAutoCommit(false);
            new PlanDraftRepository(applyConnection).createRevision(later);
            Future<Boolean> staleApproval = executor.submit(this::submitStaleApproval);

            try {
                // When
                assertThat(awaitApprovalLock(staleApproval)).isTrue();
                applyConnection.commit();

                // Then
                assertThat(staleApproval.get()).isFalse();
            } finally {
                applyConnection.rollback();
            }
        }
        assertThat(approvalCount()).isZero();
        assertThat(approvals.isCurrentRevisionEligible(context.draftId())).isFalse();
        System.out.println("DATA_SURFACE concurrent_apply_revision=2 stale_approval_revision=1 committed=false");
    }

    private PlanDraftApprovalRepository.Approval approval(
            String scope, UUID partId, UUID actorId, PlanDraftRevision target) {
        return new PlanDraftApprovalRepository.Approval(
                UUID.randomUUID(), context.draftId(), context.projectId(), target.revisionId(),
                target.contentHash(), scope, partId, actorId, "approved");
    }

    private int approvalCount() throws SQLException {
        try (var statement = database.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM plan_draft_approvals")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private boolean submitAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        try (Connection contender = connection()) {
            new PlanDraftApprovalRepository(contender).submit(
                    approval("project", null, context.ownerId(), revision));
            return true;
        } catch (SQLException expectedConstraintFailure) {
            return false;
        }
    }

    private boolean submitStaleApproval() {
        try (Connection contender = connection()) {
            contender.createStatement().execute("SET application_name = 'task11-stale-approval'");
            new PlanDraftApprovalRepository(contender).submit(
                    approval("project", null, context.ownerId(), revision));
            return true;
        } catch (SQLException expectedConstraintFailure) {
            return false;
        }
    }

    private boolean awaitApprovalLock(Future<Boolean> staleApproval) throws SQLException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !staleApproval.isDone()) {
            try (var statement = database.prepareStatement("""
                    SELECT 1
                    FROM pg_stat_activity
                    WHERE application_name = 'task11-stale-approval'
                      AND wait_event_type = 'Lock'
                    """);
                    var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return true;
                }
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
