package com.example.demo.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.domain.ActionConfirmation;
import com.example.demo.agent.domain.CareEvent;
import com.example.demo.agent.repository.ActionConfirmationRepository;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.ai.AgentContext;
import com.example.demo.ai.ToolBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ActionConfirmationService {

    private final ActionConfirmationRepository repository;
    private final CareEventRepository eventRepository;
    private final ToolBroker toolBroker;
    private final TransactionTemplate transactionTemplate;

    public ActionConfirmationService(ActionConfirmationRepository repository,
                                     CareEventRepository eventRepository,
                                     ToolBroker toolBroker,
                                     TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.toolBroker = toolBroker;
        this.transactionTemplate = transactionTemplate;
    }

    public ActionConfirmation owned(Long id, String userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("确认事项不存在或不属于当前用户"));
    }

    public ConfirmationOutcome confirm(Long id, String userId) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = repository.claimForExecution(id, userId,
                List.of(ActionConfirmation.Status.PENDING, ActionConfirmation.Status.FAILED),
                ActionConfirmation.Status.EXECUTING, now);
        if (claimed == 0) {
            throw rejectionFor(id, userId, now);
        }

        ActionConfirmation claimedRow = owned(id, userId);
        JSONObject arguments = JSON.parseObject(claimedRow.getPayloadJson());
        String subjectType = arguments.containsKey("targetType")
                ? String.valueOf(arguments.get("targetType")).toUpperCase()
                : null;
        AgentContext context = new AgentContext(
                claimedRow.getUserId(),
                claimedRow.getConversationId(),
                null,
                claimedRow.getSubjectId()
        ).confirmed(claimedRow.getId(), Set.of(AgentContext.Permission.AGENT_WRITE));

        String result;
        try {
            result = toolBroker.execute(
                    claimedRow.getToolName(),
                    arguments,
                    "confirmation_" + claimedRow.getId(),
                    context
            );
        } catch (Exception e) {
            String error = failureMessage(e);
            repository.markFailed(id, ActionConfirmation.Status.EXECUTING,
                    ActionConfirmation.Status.FAILED, error);
            throw new IllegalStateException("操作执行失败：" + error + "，可重新点击确认重试", e);
        }

        Long confirmationId = claimedRow.getId();
        ActionConfirmation executed = transactionTemplate.execute(tx -> {
            ActionConfirmation fresh = owned(confirmationId, userId);
            fresh.setStatus(ActionConfirmation.Status.EXECUTED);
            fresh.setExecutedAt(now);
            fresh.setResult(result);
            fresh.setErrorMessage(null);
            ActionConfirmation saved = repository.save(fresh);
            eventRepository.save(CareEvent.builder()
                    .eventId("evt_" + UUID.randomUUID())
                    .userId(saved.getUserId())
                    .subjectType(subjectType)
                    .subjectId(saved.getSubjectId())
                    .eventType(saved.getActionType())
                    .payload(saved.getPayloadJson())
                    .source("AGENT_CONFIRMATION")
                    .sourceEventId("confirmation_" + saved.getId())
                    .occurredAt(now)
                    .build());
            return saved;
        });
        return outcome(executed, result);
    }

    public ConfirmationOutcome cancel(Long id, String userId) {
        int cancelled = repository.cancelPending(id, userId,
                ActionConfirmation.Status.PENDING, ActionConfirmation.Status.CANCELLED);
        if (cancelled == 0) {
            throw rejectionFor(id, userId, LocalDateTime.now());
        }
        return outcome(owned(id, userId), "操作已取消");
    }

    /** 定时把已过期的 PENDING/FAILED 置为 EXPIRED，替代用户点击时的惰性置位。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireOverdueConfirmations() {
        int expired = repository.expireOverdue(
                List.of(ActionConfirmation.Status.PENDING, ActionConfirmation.Status.FAILED),
                ActionConfirmation.Status.EXPIRED, LocalDateTime.now());
        if (expired > 0) {
            log.info("Expired {} overdue action confirmation(s)", expired);
        }
    }

    private IllegalArgumentException rejectionFor(Long id, String userId, LocalDateTime now) {
        ActionConfirmation confirmation = owned(id, userId);
        if ((confirmation.getStatus() == ActionConfirmation.Status.PENDING
                || confirmation.getStatus() == ActionConfirmation.Status.FAILED)
                && !confirmation.getExpiresAt().isAfter(now)) {
            confirmation.setStatus(ActionConfirmation.Status.EXPIRED);
            repository.save(confirmation);
            return new IllegalArgumentException("确认已超时，请重新发起操作");
        }
        return switch (confirmation.getStatus()) {
            case EXECUTING -> new IllegalArgumentException("该操作正在执行，请稍候查看结果");
            case EXECUTED -> new IllegalArgumentException("该操作已执行，请勿重复操作");
            case CANCELLED -> new IllegalArgumentException("该操作已取消");
            case EXPIRED -> new IllegalArgumentException("确认已超时，请重新发起操作");
            case FAILED -> new IllegalArgumentException("该操作此前执行失败，可重新点击确认重试");
            case CONFIRMED -> new IllegalArgumentException("该操作已处理");
            case PENDING -> new IllegalArgumentException("该操作暂时无法执行，请稍后重试");
        };
    }

    private String failureMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "工具执行失败" : message;
    }

    public Card card(ActionConfirmation confirmation) {
        JSONObject payload = JSON.parseObject(confirmation.getPayloadJson());
        payload.put("confirmationId", confirmation.getId());
        payload.put("toolName", confirmation.getToolName());
        payload.put("subjectId", confirmation.getSubjectId());
        payload.put("expiresAt", confirmation.getExpiresAt().toString());
        return switch (confirmation.getActionType()) {
            case "REMINDER_CREATE" -> new Card("REMINDER_CONFIRM", "确认创建护理提醒", payload);
            case "REMINDER_COMPLETE" -> new Card("REMINDER_COMPLETE", "确认完成护理提醒", payload);
            case "MEDICATION_SAVE" -> new Card("MEDICATION_CONFIRM", "确认保存用药记录", payload);
            case "CARE_PLAN_GENERATE" -> new Card("CARE_PLAN_CONFIRM", "确认生成护理计划", payload);
            case "IMAGE_GENERATE" -> new Card("IMAGE_GENERATE_CONFIRM", "确认生成图片", payload);
            case "IMAGE_EDIT" -> new Card("IMAGE_EDIT_CONFIRM", "确认编辑图片", payload);
            case "DIAGNOSIS_SAVE" -> new Card("DIAGNOSIS_CONFIRM", "确认保存诊断记录", payload);
            default -> new Card("ACTION_CONFIRM", "确认护理操作", payload);
        };
    }

    private ConfirmationOutcome outcome(ActionConfirmation confirmation, String reply) {
        Card card = card(confirmation);
        return new ConfirmationOutcome(
                confirmation.getId(),
                confirmation.getStatus().name(),
                reply,
                card.type(),
                card.title(),
                card.payload()
        );
    }

    public Long confirmationId(String marker) {
        int start = marker.indexOf("[CONFIRMATION:");
        if (start < 0) {
            return null;
        }
        int end = marker.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        try {
            return Long.valueOf(marker.substring(start + 15, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Card(String type, String title, JSONObject payload) {
    }

    public record ConfirmationOutcome(
            Long confirmationId,
            String status,
            String reply,
            String cardType,
            String cardTitle,
            Map<String, Object> payload
    ) {
    }
}
