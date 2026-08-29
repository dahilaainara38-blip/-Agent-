package com.example.demo.ai;

/**
 * Explicit execution context propagated to tools by the Agent Runtime.
 */
public record AgentContext(
        String userId,
        String conversationId,
        String subjectType,
        Long subjectId
) {

    public static AgentContext anonymous() {
        return new AgentContext(null, null, null, null);
    }

    public boolean authenticated() {
        return userId != null && !userId.isBlank();
    }
}
