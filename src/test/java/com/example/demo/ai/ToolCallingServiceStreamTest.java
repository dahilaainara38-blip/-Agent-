package com.example.demo.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.repository.ActionConfirmationRepository;
import com.example.demo.agent.repository.ToolTraceRepository;
import com.example.demo.agent.tools.ImageAnalysisTool;
import com.example.demo.agent.tools.ImageEditTool;
import com.example.demo.agent.tools.ImageGenerationTool;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.care.service.CareAdvancedService;
import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.NearbyServiceSearchService;
import com.example.demo.care.service.PetCareQueryService;
import com.example.demo.care.service.PetFoodSafetyService;
import com.example.demo.care.service.PlantSafetyQueryService;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.UserSessionService;
import com.example.demo.disease.DiseaseRecognitionService;
import com.example.demo.imagegen.ImageGenerationService;
import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallingServiceStreamTest {

    @Test
    void streamListenerReceivesPhasesToolResultsAndDeltas() throws Exception {
        LlmService llmService = mock(LlmService.class);
        WeatherService weatherService = mock(WeatherService.class);
        when(weatherService.getWeatherByCity("北京")).thenReturn(null);

        // 第一轮：流式返回 tool_call（无 delta）；第二轮：流式文本（delta 实时推送）
        when(llmService.chatWithToolsStream(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = invocation.getArgument(2);
                    onDelta.accept("今天");
                    onDelta.accept("北京晴。");
                    return textResponse("今天北京晴。");
                })
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = invocation.getArgument(2);
                    onDelta.accept("今天");
                    onDelta.accept("北京晴。");
                    return textResponse("今天北京晴。");
                });

        ToolCallingService service = service(llmService, weatherService);

        List<String> phases = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        List<ToolCallResult> toolResults = new ArrayList<>();
        ToolCallingService.StreamListener listener = new ToolCallingService.StreamListener() {
            @Override
            public void onPhase(String phase, String detail) {
                phases.add(phase);
            }

            @Override
            public void onDelta(String text) {
                deltas.add(text);
            }

            @Override
            public void onToolResult(ToolCallResult result) {
                toolResults.add(result);
            }
        };

        ToolCallResponse response = service.chatWithMessagesStream(
                new JSONArray(), Set.of("queryWeather"), AgentContext.anonymous(), listener);

        assertEquals("今天北京晴。", response.getText());
        assertEquals(List.of("今天", "北京晴。"), deltas);
        org.mockito.Mockito.verify(llmService, org.mockito.Mockito.never())
                .chatWithTools(any(), any());
    }

    private ToolCallingService service(LlmService llmService, WeatherService weatherService) {
        SpringAiTools tools = new SpringAiTools(
                mock(WeatherService.class),
                mock(WebSearchTool.class),
                mock(ImageAnalysisTool.class),
                mock(ImageGenerationTool.class),
                mock(ImageEditTool.class),
                mock(CareReminderService.class),
                mock(CareRecordService.class),
                mock(CareAdvancedService.class),
                mock(PetCareQueryService.class),
                mock(PlantSafetyQueryService.class),
                mock(PetFoodSafetyService.class),
                mock(NearbyServiceSearchService.class),
                mock(DiseaseRecognitionService.class),
                mock(UserSessionService.class),
                mock(ArtifactService.class),
                mock(ImageGenerationService.class)
        );
        ToolBroker toolBroker = new ToolBroker(
                tools,
                mock(AgentCareTools.class),
                weatherService,
                mock(ToolTraceRepository.class),
                mock(ActionConfirmationRepository.class),
                2000,
                0
        );
        return new ToolCallingService(llmService, toolBroker, 10_000);
    }

    private JSONObject textResponse(String text) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", text);
        JSONObject choice = new JSONObject();
        choice.put("message", message);
        JSONObject response = new JSONObject();
        response.put("choices", new JSONArray(List.of(choice)));
        return response;
    }
}
