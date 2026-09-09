package com.example.demo.care;

import com.example.demo.agent.service.CareEventRecorder;
import com.example.demo.chat.entity.CareRecord;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.vision.VisionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareControllerTest {

    private LegacyCareRecordRepository careRecordRepository;
    private CareEventRecorder careEventRecorder;
    private CareController controller;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        careRecordRepository = mock(LegacyCareRecordRepository.class);
        careEventRecorder = mock(CareEventRecorder.class);
        controller = new CareController(
                mock(VisionService.class),
                mock(PlantProfileRepository.class),
                mock(PetProfileRepository.class),
                careRecordRepository,
                mock(com.example.demo.care.service.CareReminderService.class),
                careEventRecorder
        );
        session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn("user-1");
    }

    @Test
    void createRecordRequiresLogin() {
        when(session.getAttribute("user")).thenReturn(null);

        var result = controller.createRecord(Map.of(
                "targetType", "PET", "targetId", 12, "recordType", "喂食", "content", "今天喂了罐头"), session);

        assertEquals("未登录", result.getMessage());
        verify(careRecordRepository, never()).save(any(CareRecord.class));
        verify(careEventRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createRecordEmitsCareEventForTimeline() {
        when(careRecordRepository.save(any(CareRecord.class))).thenAnswer(invocation -> {
            CareRecord record = invocation.getArgument(0);
            record.setId(5L);
            return record;
        });

        var result = controller.createRecord(Map.of(
                "targetType", "PET", "targetId", 12, "recordType", "喂食", "content", "今天喂了罐头"), session);

        assertEquals(200, result.getCode());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(careEventRecorder).record(eq("user-1"), eq("PET"), eq(12L),
                eq("CARE_RECORD_SAVE"), payload.capture(), eq("REST"), eq("rest_record_5"));
        assertTrue(payload.getValue().toString().contains("今天喂了罐头"));
    }
}
