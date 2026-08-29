package com.example.demo.agent.repository;

import com.example.demo.agent.domain.CareSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareSubjectRepository extends JpaRepository<CareSubject, Long> {
    List<CareSubject> findByUserIdAndActiveTrueOrderByUpdatedAtDesc(String userId);
    Optional<CareSubject> findByIdAndUserId(Long id, String userId);
    Optional<CareSubject> findBySourceTypeAndSourceId(CareSubject.SourceType sourceType, Long sourceId);
}
