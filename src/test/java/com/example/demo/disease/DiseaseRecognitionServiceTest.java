package com.example.demo.disease;

import com.example.demo.care.model.IdentifyHistory;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import com.example.demo.vision.VisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiseaseRecognitionServiceTest {

    private VisionService visionService;
    private IdentifyHistoryRepository historyRepository;
    private DiseaseRecognitionService service;

    @BeforeEach
    void setUp() {
        visionService = mock(VisionService.class);
        historyRepository = mock(IdentifyHistoryRepository.class);
        service = new DiseaseRecognitionService(visionService, historyRepository);
    }

    @Test
    void diagnoseIsPureAnalysisAndDoesNotPersistHistory() throws Exception {
        when(visionService.analyzeImageWithCustomPrompt(any(byte[].class), anyString()))
                .thenReturn("{\"diseaseName\":\"白粉病\",\"confidence\":\"HIGH\"}");

        var result = service.diagnose(new byte[]{1, 2}, "plant", "user-1");

        assertEquals("白粉病", result.getDiseaseName());
        verify(historyRepository, never()).save(any(IdentifyHistory.class));
    }

    @Test
    void saveHistoryPersistsDiagnosisRowForExplicitSaves() {
        when(historyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveHistory("user-1", null, "plant", "{\"diseaseName\":\"白粉病\"}");

        ArgumentCaptor<IdentifyHistory> captor = ArgumentCaptor.forClass(IdentifyHistory.class);
        verify(historyRepository).save(captor.capture());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals("DISEASE", captor.getValue().getIdentifyType());
        assertTrue(captor.getValue().getMetadata().contains("\"type\":\"plant\""));
    }
}
