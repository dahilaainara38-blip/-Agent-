package com.example.demo.agent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.domain.ActionConfirmation;
import com.example.demo.agent.domain.CareEvent;
import com.example.demo.agent.repository.ActionConfirmationRepository;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.ai.AgentContext;
import com.example.demo.ai.ToolBroker;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ActionConfirmationService {

    private final ActionConfirmationRepository repository;
    private final CareEventRepository eventRepository;
    private final ToolBroker toolBroker;

    public ActionConfirmationService(ActionConfirmationRepository repository,
                                     CareEventRepository eventRepository,
                                     ToolBroker toolBroker) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.toolBroker = toolBroker;
    }

    public ActionConfirmation owned(Long id, String userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("确认事项不存在或不属于当前用户"));
    }

    public ConfirmationOutcome confirm(Long id, String userId) {
        ActionConfirmation confirmation = owned(id, userId);
        if (confirmation.getStatus() != ActionConfirmation.Status.PENDING) {
            throw new IllegalArgumentException("该操作已处理");
        }
        if (confirmation.getExpiresAt().isBefore(LocalDateTime.now())) {
            confirmation.setStatus(ActionConfirmation.Status.EXPIRED);
            repository.save(confirmation);
            throw new IllegalArgumentException("确认已超时，请重新发起操作");
        }

        confirmation.setStatus(ActionConfirmation.Status.CONFIRMED);
        confirmation = repository.save(confirmation);

        JSONObject arguments = JSON.parseObject(confirmation.getPayloadJson());
        String subjectType = arguments.containsKey("targetType")
                ? String.valueOf(arguments.get("targetType")).toUpperCase()
                : null;
        AgentContext context = new AgentContext(
                confirmation.getUserId(),
                confirmation.getConversationId(),
                null,
                confirmation.getSubjectId()
        ).confirmed(confirmation.getId(), Set.of(AgentContext.Permission.AGENT_WRITE));
        String result = toolBroker.execute(
                confirmation.getToolName(),
                arguments,
                "confirmation_" + confirmation.getId(),
                context
        );

        confirmation.setStatus(ActionConfirmation.Status.EXECUTED);
        confirmation.setExecutedAt(LocalDateTime.now());
        confirmation.setResult(result);
        confirmation = repository.save(confirmation);

        eventRepository.save(CareEvent.builder()
                .eventId("evt_" + UUID.randomUUID())
                .userId(confirmation.getUserId())
                .subjectType(subjectType)
                .subjectId(confirmation.getSubjectId())
                .eventType(confirmation.getActionType())
                .payload(confirmation.getPayloadJson())
                .source("AGENT_CONFIRMATION")
                .sourceEventId("confirmation_" + confirmation.getId())
                .occurredAt(LocalDateTime.now())
                .build());

        return outcome(confirmation, result);
    }

    public ConfirmationOutcome cancel(Long id, String userId) {
        ActionConfirmation confirmation = owned(id, userId);
        if (confirmation.getStatus() != ActionConfirmation.Status.PENDING) {
            throw new IllegalArgumentException("该操作已处理");
        }
        confirmation.setStatus(ActionConfirmation.Status.CANCELLED);
        confirmation = repository.save(confirmation);
        return outcome(confirmation, "操作已取消");
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
