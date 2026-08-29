package com.example.demo.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ImageAnalysisTool;
import com.example.demo.agent.tools.ImageEditTool;
import com.example.demo.agent.tools.ImageGenerationTool;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.care.service.CareAdvancedService;
import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.NearbyServiceSearchService;
import com.example.demo.care.service.PetCareQueryService;
import com.example.demo.care.service.PetFoodSafetyService;
import com.example.demo.care.service.PlantSafetyQueryService;
import com.example.demo.chat.UserSessionService;
import com.example.demo.chat.LlmService;
import com.example.demo.disease.DiseaseRecognitionService;
import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallingServiceAgentContextTest {

    private CareReminderService careReminderService;
    private ToolCallingService toolCallingService;

    @BeforeEach
    void setUp() {
        careReminderService = mock(CareReminderService.class);

        SpringAiTools tools = new SpringAiTools(
                mock(WeatherService.class),
                mock(WebSearchTool.class),
                mock(ImageAnalysisTool.class),
                mock(ImageGenerationTool.class),
                mock(ImageEditTool.class),
                careReminderService,
                mock(CareRecordService.class),
                mock(CareAdvancedService.class),
                mock(PetCareQueryService.class),
                mock(PlantSafetyQueryService.class),
                mock(PetFoodSafetyService.class),
                mock(NearbyServiceSearchService.class),
                mock(DiseaseRecognitionService.class),
                mock(UserSessionService.class)
        );

        toolCallingService = new ToolCallingService(
                mock(LlmService.class),
                tools,
                mock(WeatherService.class)
        );
    }

    @Test
    void agentContextIsHiddenFromToolSchema() {
        JSONArray schema = toolCallingService.buildToolsSchema(Set.of("completeCareReminder"));

        JSONObject function = schema.getJSONObject(0).getJSONObject("function");
        JSONObject properties = function.getJSONObject("parameters").getJSONObject("properties");
        JSONArray required = function.getJSONObject("parameters").getJSONArray("required");

        assertFalse(properties.containsKey("context"));
        assertFalse(properties.containsKey("userId"));
        assertTrue(required == null || required.isEmpty());
    }

    @Test
    void agentContextIsInjectedIntoToolExecution() {
        AgentContext context = new AgentContext(
                "user-1",
                "conversation-1",
                "PET",
                12L
        );
        when(careReminderService.completeLatestReminder("user-1")).thenReturn("提醒已完成");

        String result = toolCallingService.executeTool(
                "completeCareReminder",
                new JSONObject(),
                context
        );

        assertTrue(result.contains("提醒已完成"));
        verify(careReminderService).completeLatestReminder("user-1");
    }
}
