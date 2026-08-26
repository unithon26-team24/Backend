package com.uniton.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FoundationMigrationTest extends PostgresIntegrationSupport {

    private ProjectRepository repository;

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        cleanAndMigrate();
        repository = new ProjectRepository(connection());
    }

    @Test
    void persistsFoundationGraphWhenProjectSetupIsValid() throws SQLException {
        // Given
        UUID projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();

        // When
        repository.createProject(new ProjectRepository.ProjectSetup(
                projectId,
                "유니톤",
                "검증 가능한 최종 산출물",
                Instant.parse("2026-09-01T09:00:00Z"),
                "Asia/Seoul",
                ownerId,
                "U-OWNER",
                "Owner",
                List.of("기획", "의사결정")));
        repository.addMember(projectId, memberId, "U-MEMBER", "Member", List.of("백엔드"));
        repository.addPart(projectId, partId, "Backend", memberId);
        repository.selectSharedReference(projectId, "slack", "slack_channel", "C-PROJECT");
        repository.selectSharedReference(projectId, "notion", "notion_parent", "page-root");

        // Then
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT p.planning_time_zone, pm.member_role, ppm.part_role,
                               array_agg(pr.provider ORDER BY pr.provider) AS providers
                        FROM projects p
                        JOIN project_members pm ON pm.id = p.owner_member_id
                        JOIN project_part_memberships ppm ON ppm.project_id = p.id
                        JOIN project_resource_references pr ON pr.project_id = p.id
                        WHERE p.id = ? AND ppm.member_id = ?
                        GROUP BY p.planning_time_zone, pm.member_role, ppm.part_role
                        """)) {
            statement.setObject(1, projectId);
            statement.setObject(2, memberId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("planning_time_zone")).isEqualTo("Asia/Seoul");
                assertThat(rows.getString("member_role")).isEqualTo("owner");
                assertThat(rows.getString("part_role")).isEqualTo("part_lead");
                assertThat((String[]) rows.getArray("providers").getArray())
                        .containsExactly("notion", "slack");
                System.out.println(
                        "DATA_SURFACE foundation timezone=Asia/Seoul owner=owner part=part_lead references=notion,slack");
            }
        }
    }

    @Test
    void rejectsProjectWhenPlanningTimezoneIsNotIana() throws SQLException {
        // Given
        ProjectRepository.ProjectSetup setup = new ProjectRepository.ProjectSetup(
                UUID.randomUUID(), "Invalid", "Goal", Instant.now(), "KST", UUID.randomUUID(),
                "U1", "Owner", List.of("기획"));

        // When / Then
        assertThatThrownBy(() -> repository.createProject(setup))
                .isInstanceOf(SQLException.class);
        assertThat(projectCount()).isZero();
        System.out.println("DATA_SURFACE invalid_timezone=rejected leaked_projects=0");
    }

    @Test
    void rejectsControlCharacterResponsibilityLabels() throws SQLException {
        // Given
        ProjectRepository.ProjectSetup setup = new ProjectRepository.ProjectSetup(
                UUID.randomUUID(), "Invalid", "Goal", Instant.now(), "Asia/Seoul", UUID.randomUUID(),
                "U1", "Owner", List.of("line\nbreak"));

        // When / Then
        assertThatThrownBy(() -> repository.createProject(setup))
                .isInstanceOf(SQLException.class);
        assertThat(projectCount()).isZero();
        System.out.println("DATA_SURFACE malformed_labels=rejected leaked_projects=0");
    }

    @Test
    void rejectsResponsibilityLabelsBeyondPlaintextBound() throws SQLException {
        // Given
        ProjectRepository.ProjectSetup setup = new ProjectRepository.ProjectSetup(
                UUID.randomUUID(), "Invalid", "Goal", Instant.now(), "Asia/Seoul", UUID.randomUUID(),
                "U1", "Owner", List.of("가".repeat(65)));

        // When / Then
        assertThatThrownBy(() -> repository.createProject(setup))
                .isInstanceOf(SQLException.class);
        assertThat(projectCount()).isZero();
        System.out.println("DATA_SURFACE oversized_label=rejected leaked_projects=0");
    }

    @Test
    void rejectsDisallowedSharedResourceReference() throws SQLException {
        // Given
        UUID projectId = UUID.randomUUID();
        repository.createProject(new ProjectRepository.ProjectSetup(
                projectId, "Project", "Goal", Instant.now(), "UTC", UUID.randomUUID(),
                "U1", "Owner", List.of("기획")));

        // When / Then
        assertThatThrownBy(() -> repository.selectSharedReference(
                        projectId, "slack", "notion_parent", "cross-provider"))
                .isInstanceOf(SQLException.class);
        System.out.println("DATA_SURFACE mismatched_resource_reference=rejected");
    }

    @Test
    void failedPartLeadMembershipDoesNotLeavePart() throws SQLException {
        // Given
        UUID projectId = UUID.randomUUID();
        repository.createProject(new ProjectRepository.ProjectSetup(
                projectId, "Project", "Goal", Instant.now(), "UTC", UUID.randomUUID(),
                "U1", "Owner", List.of("기획")));

        // When / Then
        try (Connection transactionConnection = connection()) {
            ProjectRepository transactionalRepository = new ProjectRepository(transactionConnection);
            assertThatThrownBy(() -> transactionalRepository.addPart(
                            projectId, UUID.randomUUID(), "Orphan candidate", UUID.randomUUID()))
                    .isInstanceOf(SQLException.class);
            assertThat(transactionConnection.getAutoCommit()).isTrue();
        }
        assertThat(partCount()).isZero();
        System.out.println("DATA_SURFACE failed_part_lead_membership leaked_parts=0 auto_commit=restored");
    }

    @Test
    void soleOwnerCannotBeDemotedOrDeleted() throws SQLException {
        // Given
        UUID projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createProject(new ProjectRepository.ProjectSetup(
                projectId, "Project", "Goal", Instant.now(), "UTC", ownerId,
                "U1", "Owner", List.of("기획")));

        // When / Then
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> updateMemberRole(connection, ownerId, "member"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> deleteMember(connection, ownerId))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(ownerCount(projectId)).isEqualTo(1);
        System.out.println("DATA_SURFACE sole_owner_demotion=rejected sole_owner_delete=rejected owners=1");
    }

    private int projectCount() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM projects")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int partCount() throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM project_parts")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void updateMemberRole(Connection connection, UUID memberId, String role) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE project_members SET member_role = ? WHERE id = ?")) {
            statement.setString(1, role);
            statement.setObject(2, memberId);
            statement.executeUpdate();
        }
    }

    private void deleteMember(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM project_members WHERE id = ?")) {
            statement.setObject(1, memberId);
            statement.executeUpdate();
        }
    }

    private int ownerCount(UUID projectId) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("""
                        SELECT count(*) FROM project_members
                        WHERE project_id = ? AND member_role = 'owner'
                        """)) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
