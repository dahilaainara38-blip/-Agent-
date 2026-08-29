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
@Table(name = "care_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareEvent {

    @Id
    @Column(name = "event_id", length = 80)
    private String eventId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "subject_type", length = 20)
    private String subjectType;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "artifact_id", length = 80)
    private String artifactId;

    @Column(name = "source", nullable = false, length = 40)
    private String source;

    @Column(name = "source_event_id", length = 80)
    private String sourceEventId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        createdAt = LocalDateTime.now();
    }
}
