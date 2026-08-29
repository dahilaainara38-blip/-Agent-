package com.example.demo.agent.repository;

import com.example.demo.agent.domain.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<Artifact, String> {
    Optional<Artifact> findByIdAndUserId(String id, String userId);
}
