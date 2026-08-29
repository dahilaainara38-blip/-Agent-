package com.example.demo.ai.runtime;

import java.util.Set;

public record AgentChatRequest(
        String conversationId,
        String message,
        String artifactId,
        String imageBase64,
        String subjectType,
        Long subjectId,
        Set<String> allowedTools
) {
}
