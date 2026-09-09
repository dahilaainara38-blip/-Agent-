package com.example.demo.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "care_subject")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "species", length = 100)
    private String species;

    @Column(name = "breed", length = 100)
    private String breed;

    @Column(name = "profile", columnDefinition = "TEXT")
    private String profile;

    @Column(name = "source_type", nullable = false, length = 40)
    private SourceType sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum SubjectType {
        PET, PLANT
    }

    public enum SourceType {
        CARE_TARGET, PET_PROFILE, PLANT_PROFILE, AGENT
    }
}
