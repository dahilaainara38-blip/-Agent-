package com.example.demo.agent.service;

import com.example.demo.agent.domain.AgentConversation;
import com.example.demo.agent.domain.AgentMessage;
import com.example.demo.agent.repository.AgentConversationRepository;
import com.example.demo.agent.repository.AgentMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentConversationService {

    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;

    public AgentConversationService(AgentConversationRepository conversationRepository,
                                    AgentMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public AgentConversation create(String userId, Long subjectId) {
        String conversationId = "agent_" + UUID.randomUUID();
        return conversationRepository.save(AgentConversation.builder()
                .conversationId(conversationId)
                .userId(userId)
                .currentSubjectId(subjectId)
                .build());
    }

    @Transactional
    public AgentConversation resolve(String requestedConversationId, String userId, Long subjectId) {
        if (requestedConversationId != null && !requestedConversationId.isBlank()) {
            AgentConversation conversation = conversationRepository
                    .findByConversationIdAndUserId(requestedConversationId.trim(), userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户"));
            conversation.setCurrentSubjectId(subjectId);
            return conversation;
        }

        return create(userId, subjectId);
    }

    @Transactional(readOnly = true)
    public List<AgentMessage> history(String conversationId, String userId, int limit) {
        List<AgentMessage> messages = messageRepository
                .findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
        int skip = Math.max(0, messages.size() - limit);
        return messages.stream().skip(skip).toList();
    }

    @Transactional
    public AgentMessage append(String conversationId, String userId, String role,
                               String content, String artifactId, String traceId) {
        AgentConversation conversation = conversationRepository
                .findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户"));
        if (conversation.getSummary() == null || conversation.getSummary().isBlank()) {
            conversation.setSummary(summarize(content));
        }
        conversation.setUpdatedAt(LocalDateTime.now());
        return messageRepository.save(AgentMessage.builder()
                .messageId("msg_" + UUID.randomUUID())
                .conversationId(conversationId)
                .userId(userId)
                .role(role)
                .content(content == null ? "" : content)
                .artifactId(artifactId)
                .traceId(traceId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<AgentConversation> recent(String userId) {
        return conversationRepository.findTop20ByUserIdOrderByUpdatedAtDesc(userId);
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
