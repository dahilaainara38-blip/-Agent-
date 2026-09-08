package com.example.demo.agent.service;

import com.alibaba.fastjson2.JSON;
import com.example.demo.agent.domain.CareEvent;
import com.example.demo.agent.repository.CareEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域事件旁路落库：REST 写路径、定时投递等与确认执行共用，
 * 让 care_event 成为跨来源的完整护理时间线。写入失败只记错误日志，不阻断主流程。
 */
@Slf4j
@Component
public class CareEventRecorder {

    private final CareEventRepository repository;

    public CareEventRecorder(CareEventRepository repository) {
        this.repository = repository;
    }

    public void record(String userId, String subjectType, Long subjectId,
                       String eventType, Object payload, String source, String sourceEventId) {
        try {
            repository.save(CareEvent.builder()
                    .eventId("evt_" + UUID.randomUUID())
                    .userId(userId)
                    .subjectType(subjectType)
                    .subjectId(subjectId)
                    .eventType(eventType)
                    .payload(payload instanceof String text ? text : JSON.toJSONString(payload))
                    .source(source)
                    .sourceEventId(sourceEventId)
                    .occurredAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to record care event {} for user {} (sourceEventId={})",
                    eventType, userId, sourceEventId, e);
        }
    }
}
