package com.example.demo.agent.repository;

import com.example.demo.agent.domain.CareEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CareEventRepository extends JpaRepository<CareEvent, String> {
    List<CareEvent> findByUserIdAndSubjectIdOrderByOccurredAtDesc(String userId, Long subjectId);
}
