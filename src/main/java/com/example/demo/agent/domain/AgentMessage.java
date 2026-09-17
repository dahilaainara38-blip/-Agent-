package com.example.demo.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentMessage {

    @Id
    @Column(name = "message_id", length = 80)
    private String messageId;

    @Column(name = "conversation_id", nullable = false, length = 80)
    private String conversationId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    // 显式 TEXT：@Lob 在方言间映射不稳定，且 ddl-auto=update 不会迁移已有列类型，
    // 已有库由 care_schema.sql 的 ALTER 兜底
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "artifact_id", length = 80)
    private String artifactId;

    @Column(name = "trace_id", length = 80)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
