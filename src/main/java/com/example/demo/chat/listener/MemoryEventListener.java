package com.example.demo.chat.listener;

import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.chat.event.SummaryUpdateEvent;
import com.example.demo.chat.event.VectorSaveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MemoryEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryEventListener.class);
    
    private final VectorStoreService vectorStoreService;
    private final ChatMemoryService chatMemoryService;
    
    public MemoryEventListener(VectorStoreService vectorStoreService, ChatMemoryService chatMemoryService) {
        this.vectorStoreService = vectorStoreService;
        this.chatMemoryService = chatMemoryService;
    }
    
    @EventListener
    @Async("vectorTaskExecutor")
    public void handleVectorSaveEvent(VectorSaveEvent event) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                logger.debug("Processing VectorSaveEvent for conversation: {}", event.getConversationId());
                vectorStoreService.saveMessageForUser(event.getConversationId(),
                        event.getOwnerId(), event.getUserMessage(), event.getAssistantReply());
                if (attempt > 1) {
                    logger.info("Vector save recovered on attempt {} for conversation: {}",
                            attempt, event.getConversationId());
                }
                return;
            } catch (Exception e) {
                if (attempt >= 2) {
                    logger.error("Failed to save vector asynchronously for conversation: {}",
                            event.getConversationId(), e);
                    return;
                }
                logger.warn("Vector save attempt 1 failed for conversation: {}, retrying: {}",
                        event.getConversationId(), e.getMessage());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    @EventListener
    @Async("summaryTaskExecutor")
    public void handleSummaryUpdateEvent(SummaryUpdateEvent event) {
        try {
            logger.debug("Processing SummaryUpdateEvent for conversation: {}", event.getConversationId());
            chatMemoryService.checkAndUpdateSummary(event.getConversationId());
            logger.debug("Summary updated successfully for conversation: {}", event.getConversationId());
        } catch (Exception e) {
            logger.error("Failed to update summary asynchronously for conversation: {}", 
                    event.getConversationId(), e);
        }
    }
}
