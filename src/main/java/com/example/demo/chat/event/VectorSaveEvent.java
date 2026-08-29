package com.example.demo.chat.event;

public class VectorSaveEvent {
    
    private final String conversationId;
    private final String userMessage;
    private final String assistantReply;
    private final String ownerId;
    
    public VectorSaveEvent(String conversationId, String userMessage, String assistantReply) {
        this(conversationId, userMessage, assistantReply, null);
    }

    public VectorSaveEvent(String conversationId, String userMessage, String assistantReply, String ownerId) {
        this.conversationId = conversationId;
        this.userMessage = userMessage;
        this.assistantReply = assistantReply;
        this.ownerId = ownerId;
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    public String getUserMessage() {
        return userMessage;
    }
    
    public String getAssistantReply() {
        return assistantReply;
    }

    public String getOwnerId() {
        return ownerId;
    }
}
