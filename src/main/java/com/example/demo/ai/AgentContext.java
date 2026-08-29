package com.example.demo.ai;

import java.util.Set;

/**
 * Explicit execution context propagated to tools by the Agent Runtime.
 */
public record AgentContext(
        String userId,
        String conversationId,
        String subjectType,
        Long subjectId,
        Set<Permission> permissions,
        Long confirmedActionId,
        String artifactId
) {

    public static AgentContext anonymous() {
        return new AgentContext(null, null, null, null, Set.of(), null, null);
    }

    public AgentContext(String userId, String conversationId, String subjectType, Long subjectId) {
        this(userId, conversationId, subjectType, subjectId, Set.of(), null, null);
    }

    public boolean authenticated() {
        return userId != null && !userId.isBlank();
    }

    public boolean hasPermission(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }

    public AgentContext withArtifactId(String artifactId) {
        return new AgentContext(userId, conversationId, subjectType, subjectId,
                permissions, confirmedActionId, artifactId);
    }

    public AgentContext confirmed(Long confirmationId, Set<Permission> grantedPermissions) {
        return new AgentContext(userId, conversationId, subjectType, subjectId,
                grantedPermissions == null ? Set.of() : grantedPermissions,
                confirmationId, artifactId);
    }

    public enum Permission {
        AGENT_READ,
        AGENT_WRITE
    }
}
