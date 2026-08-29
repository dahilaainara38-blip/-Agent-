package com.example.demo.ai.runtime;

import com.example.demo.ai.ToolCallResult;

import java.util.List;

public record AgentChatResponse(
        boolean success,
        String error,
        String reply,
        String conversationId,
        String artifactId,
        String artifactUrl,
        String traceId,
        int totalIterations,
        long totalTokens,
        List<ToolCallResult> toolCallHistory,
        List<String> generatedFiles,
        List<AgentCard> cards
) {

    public static AgentChatResponse error(String error) {
        return new AgentChatResponse(false, error, null, null, null, null,
                null, 0, 0, List.of(), List.of(), List.of());
    }
}
