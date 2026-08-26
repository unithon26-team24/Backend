package com.uniton.backend.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProjectRepository {

    private final Connection connection;

    public ProjectRepository(Connection connection) {
        this.connection = connection;
    }

    public void createProject(ProjectSetup setup) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }
        try {
            try (var project = connection.prepareStatement("""
                    INSERT INTO projects
                      (id, name, goal, final_deadline_at, planning_time_zone, owner_member_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                project.setObject(1, setup.id());
                project.setString(2, setup.name());
                project.setString(3, setup.goal());
                project.setTimestamp(4, Timestamp.from(setup.finalDeadline()));
                project.setString(5, setup.planningTimezone());
                project.setObject(6, setup.ownerId());
                project.executeUpdate();
            }
            insertMember(setup.id(), setup.ownerId(), setup.ownerSlackUserId(), setup.ownerDisplayName(),
                    "owner", setup.ownerResponsibilityLabels());
            try (var settings = connection.prepareStatement(
                    "INSERT INTO project_settings (project_id) VALUES (?)")) {
                settings.setObject(1, setup.id());
                settings.executeUpdate();
            }
            appendAudit(setup.id(), setup.ownerId(), "project_created", "project", setup.id());
            if (autoCommit) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException exception) {
            if (autoCommit) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
            throw exception;
        } finally {
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    public void addMember(
            UUID projectId,
            UUID memberId,
            String slackUserId,
            String displayName,
            List<String> responsibilityLabels) throws SQLException {
        insertMember(projectId, memberId, slackUserId, displayName, "member", responsibilityLabels);
    }

    public void addPart(UUID projectId, UUID partId, String name, UUID leadMemberId) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }
        try {
            try (var part = connection.prepareStatement(
                            "INSERT INTO project_parts (id, project_id, name) VALUES (?, ?, ?)");
                    var membership = connection.prepareStatement("""
                            INSERT INTO project_part_memberships (project_id, part_id, member_id, part_role)
                            VALUES (?, ?, ?, 'part_lead')
                            """)) {
                part.setObject(1, partId);
                part.setObject(2, projectId);
                part.setString(3, name);
                part.executeUpdate();
                membership.setObject(1, projectId);
                membership.setObject(2, partId);
                membership.setObject(3, leadMemberId);
                membership.executeUpdate();
            }
            if (autoCommit) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException exception) {
            if (autoCommit) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
            throw exception;
        } finally {
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    public void selectSharedReference(
            UUID projectId, String provider, String resourceType, String logicalReference) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO project_resource_references
                  (id, project_id, provider, resource_type, logical_reference)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, projectId);
            statement.setString(3, provider);
            statement.setString(4, resourceType);
            statement.setString(5, logicalReference);
            statement.executeUpdate();
        }
    }

    private void insertMember(
            UUID projectId,
            UUID memberId,
            String slackUserId,
            String displayName,
            String memberRole,
            List<String> responsibilityLabels) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO project_members
                  (id, project_id, slack_user_id, display_name, member_role, responsibility_labels)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, projectId);
            statement.setString(3, slackUserId);
            statement.setString(4, displayName);
            statement.setString(5, memberRole);
            statement.setArray(6, connection.createArrayOf("text", responsibilityLabels.toArray()));
            statement.executeUpdate();
        }
    }

    private void appendAudit(
            UUID projectId, UUID actorId, String eventType, String subjectType, UUID subjectId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO audit_events
                  (id, project_id, actor_member_id, event_type, subject_type, subject_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, projectId);
            statement.setObject(3, actorId);
            statement.setString(4, eventType);
            statement.setString(5, subjectType);
            statement.setObject(6, subjectId);
            statement.executeUpdate();
        }
    }

    public record ProjectSetup(
            UUID id,
            String name,
            String goal,
            Instant finalDeadline,
            String planningTimezone,
            UUID ownerId,
            String ownerSlackUserId,
            String ownerDisplayName,
            List<String> ownerResponsibilityLabels) {}
}
