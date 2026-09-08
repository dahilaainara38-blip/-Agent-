package com.example.demo.care.service;

import com.example.demo.agent.service.CareEventRecorder;
import com.example.demo.care.repository.CareRecordRepository;
import com.example.demo.care.repository.CareTargetRepository;
import com.example.demo.push.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareReminderServiceTest {

    private JdbcTemplate jdbc;
    private CareEventRecorder careEventRecorder;
    private CareReminderService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        careEventRecorder = mock(CareEventRecorder.class);
        service = new CareReminderService(
                jdbc,
                mock(CareTargetRepository.class),
                mock(CareRecordRepository.class),
                mock(WebPushService.class),
                careEventRecorder
        );
    }

    @Test
    void deliveredReminderWritesTimelineEventOnce() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "id", 9L,
                "user_id", "user-1",
                "target_type", "pet",
                "target_id", 12L,
                "reminder_type", "用药",
                "content", "给咪咪滴耳药",
                "due_at", "2026-09-08 08:00:00"
        )));
        when(jdbc.update(anyString(), eq(9L))).thenReturn(1);

        service.sendDueReminders();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(careEventRecorder).record(eq("user-1"), eq("PET"), eq(12L),
                eq("REMINDER_DELIVERED"), payload.capture(), eq("SCHEDULER"), eq("reminder_delivery_9"));
    }

    @Test
    void alreadyDeliveredReminderWritesNoEvent() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
                "id", 9L,
                "user_id", "user-1",
                "reminder_type", "用药",
                "content", "给咪咪滴耳药"
        )));
        when(jdbc.update(anyString(), eq(9L))).thenReturn(0);

        service.sendDueReminders();

        verify(careEventRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
