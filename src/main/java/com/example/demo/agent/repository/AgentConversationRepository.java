package com.example.demo.agent.repository;

import com.example.demo.agent.domain.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, String> {
    Optional<AgentConversation> findByConversationIdAndUserId(String conversationId, String userId);

    List<AgentConversation> findTop20ByUserIdOrderByUpdatedAtDesc(String userId);
}
