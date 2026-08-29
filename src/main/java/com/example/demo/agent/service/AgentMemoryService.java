package com.example.demo.agent.service;

import com.example.demo.chat.VectorStoreService;
import com.example.demo.chat.event.VectorSaveEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentMemoryService {

    private final VectorStoreService vectorStoreService;
    private final ApplicationEventPublisher eventPublisher;

    public AgentMemoryService(VectorStoreService vectorStoreService,
                              ApplicationEventPublisher eventPublisher) {
        this.vectorStoreService = vectorStoreService;
        this.eventPublisher = eventPublisher;
    }

    public List<String> retrieve(String query, String userId, String conversationId) {
        return vectorStoreService.searchForUser(query, userId, conversationId);
    }

    public void remember(String conversationId, String userId,
                         String userMessage, String assistantReply) {
        eventPublisher.publishEvent(new VectorSaveEvent(
                conversationId, userMessage, assistantReply, userId
        ));
    }
}
