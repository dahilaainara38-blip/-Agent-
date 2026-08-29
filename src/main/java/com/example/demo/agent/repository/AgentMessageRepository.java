package com.example.demo.agent.repository;

import com.example.demo.agent.domain.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, String> {
    List<AgentMessage> findByConversationIdAndUserIdOrderByCreatedAtAsc(String conversationId, String userId);
}
