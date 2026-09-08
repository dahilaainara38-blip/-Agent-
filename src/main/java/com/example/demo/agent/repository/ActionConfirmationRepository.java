package com.example.demo.agent.repository;

import com.example.demo.agent.domain.ActionConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ActionConfirmationRepository extends JpaRepository<ActionConfirmation, Long> {

    Optional<ActionConfirmation> findByIdAndUserId(Long id, String userId);

    /** 原子认领：仅未过期的 PENDING/FAILED 可进入 EXECUTING，返回 0 表示被并发请求抢先或状态不符。 */
    @Transactional
    @Modifying
    @Query("update ActionConfirmation c set c.status = :target, c.errorMessage = null "
            + "where c.id = :id and c.userId = :userId and c.status in :claimable and c.expiresAt > :now")
    int claimForExecution(Long id, String userId, List<ActionConfirmation.Status> claimable,
                          ActionConfirmation.Status target, LocalDateTime now);

    @Transactional
    @Modifying
    @Query("update ActionConfirmation c set c.status = :target, c.errorMessage = :error "
            + "where c.id = :id and c.status = :source")
    int markFailed(Long id, ActionConfirmation.Status source, ActionConfirmation.Status target, String error);

    @Transactional
    @Modifying
    @Query("update ActionConfirmation c set c.status = :target "
            + "where c.id = :id and c.userId = :userId and c.status = :source")
    int cancelPending(Long id, String userId, ActionConfirmation.Status source, ActionConfirmation.Status target);

    /** 批量把已过期的 PENDING/FAILED 置为 EXPIRED，供定时任务调用。 */
    @Transactional
    @Modifying
    @Query("update ActionConfirmation c set c.status = :target "
            + "where c.status in :stale and c.expiresAt <= :now")
    int expireOverdue(List<ActionConfirmation.Status> stale, ActionConfirmation.Status target, LocalDateTime now);
}
