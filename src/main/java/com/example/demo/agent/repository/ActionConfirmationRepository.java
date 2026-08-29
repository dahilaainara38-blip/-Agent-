package com.example.demo.agent.repository;

import com.example.demo.agent.domain.ActionConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActionConfirmationRepository extends JpaRepository<ActionConfirmation, Long> {
    Optional<ActionConfirmation> findByIdAndUserId(Long id, String userId);
}
