package com.example.demo.agent.repository;

import com.example.demo.agent.domain.ToolTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;;

public interface ToolTraceRepository extends JpaRepository<ToolTrace, Long> {

    List<ToolTrace> findByConversationIdOrderByCreatedAtDesc(String conversationId);
}
