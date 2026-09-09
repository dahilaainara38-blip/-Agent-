package com.example.demo.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.care.service.NearbyServiceSearchService;
import com.example.demo.care.service.PetFoodSafetyService;
import com.example.demo.care.service.PlantSafetyQueryService;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.UserSessionService;
import com.example.demo.disease.DiseaseRecognitionService;
import com.example.demo.disease.model.DiseaseResult;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 黄金用例回归：删除关键字意图路由后，工具选择完全依赖 LLM 决策。
 * 本测试 mock LLM 的两轮响应（第一轮 tool_call，第二轮最终文本），
 * 验证真实 ToolCallingService → ToolBroker → SpringAiTools 链路能按
 * LLM 决策正确调用工具、传对参数、并把工具结果回传给下一轮对话。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoldenCaseToolRoutingTest {

    @Autowired
    private ToolCallingService toolCallingService;

    @MockitoBean
    private LlmService llmService;

    @MockitoBean
    private WeatherService weatherService;

    @MockitoBean
    private PetFoodSafetyService petFoodSafetyService;

    @MockitoBean
    private PlantSafetyQueryService plantSafetyQueryService;

    @MockitoBean
    private NearbyServiceSearchService nearbyServiceSearchService;

    @MockitoBean
    private ArtifactService artifactService;

    @MockitoBean
    private UserSessionService userSessionService;

    @MockitoBean
    private DiseaseRecognitionService diseaseRecognitionService;

    @Test
    void weatherGoldenCaseRoutesThroughBroker() throws Exception {
        when(weatherService.getWeatherByCity("北京")).thenReturn(WeatherResponse.builder()
                .city("北京")
                .current(WeatherResponse.CurrentWeather.builder()
                        .temperature(28.0).weather("晴").build())
                .build());
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("getWeather", "{\"city\":\"北京\"}"))
                .thenReturn(llmText("北京当前晴，气温 28 度。"));

        ToolCallResponse response = run("北京现在天气怎么样？");

        verify(weatherService).getWeatherByCity("北京");
        assertToolSucceeded(response, "getWeather");
        assertTrue(response.getText().contains("28"));
        assertToolResultFedBackToLlm("北京");
    }

    @Test
    void nearbyHospitalGoldenCaseRoutesThroughBroker() throws Exception {
        when(nearbyServiceSearchService.searchNearbyService("hospital", "朝阳区"))
                .thenReturn("瑞鹏宠物医院(朝阳店)\n【必须保留的导航链接】https://uri.amap.com/marker?position=116.4,39.9");
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("searchNearbyService",
                        "{\"serviceType\":\"hospital\",\"location\":\"朝阳区\"}"))
                .thenReturn(llmText("为你找到瑞鹏宠物医院(朝阳店)：https://uri.amap.com/marker?position=116.4,39.9"));

        ToolCallResponse response = run("我在朝阳区，帮我找附近的宠物医院");

        verify(nearbyServiceSearchService).searchNearbyService("hospital", "朝阳区");
        assertToolSucceeded(response, "searchNearbyService");
        assertTrue(response.getText().contains("uri.amap.com"));
        assertToolResultFedBackToLlm("瑞鹏宠物医院");
    }

    @Test
    void foodSafetyGoldenCaseRoutesThroughBroker() throws Exception {
        when(petFoodSafetyService.queryFoodSafety("巧克力", "猫"))
                .thenReturn("危险：巧克力对猫有毒，含可可碱，切勿喂食");
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("queryFoodSafety", "{\"foodName\":\"巧克力\",\"petType\":\"猫\"}"))
                .thenReturn(llmText("巧克力对猫有毒，请不要喂食。"));

        ToolCallResponse response = run("我家猫能吃巧克力吗？");

        verify(petFoodSafetyService).queryFoodSafety("巧克力", "猫");
        assertToolSucceeded(response, "queryFoodSafety");
        assertTrue(response.getText().contains("有毒"));
        assertToolResultFedBackToLlm("可可碱");
    }

    @Test
    void plantToxicityGoldenCaseRoutesThroughBroker() throws Exception {
        when(plantSafetyQueryService.queryPlantSafety("toxicity", "百合", "百合对猫有毒吗"))
                .thenReturn("百合对猫有剧毒，花粉和叶片均不可接触");
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("queryPlantSafety",
                        "{\"queryType\":\"toxicity\",\"plantName\":\"百合\",\"question\":\"百合对猫有毒吗\"}"))
                .thenReturn(llmText("百合对猫有剧毒，建议不要在家养百合。"));

        ToolCallResponse response = run("百合对猫有毒吗？");

        verify(plantSafetyQueryService).queryPlantSafety("toxicity", "百合", "百合对猫有毒吗");
        assertToolSucceeded(response, "queryPlantSafety");
        assertTrue(response.getText().contains("剧毒"));
        assertToolResultFedBackToLlm("剧毒");
    }

    @Test
    void imageUploadFollowedByQuestionInvokesArtifactAnalysis() throws Exception {
        when(artifactService.analyze("art-1", "golden-user", "这是什么植物？"))
                .thenReturn("图中是一盆健康的绿萝，叶片舒展无病斑。");
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("analyzeImage", "{\"prompt\":\"这是什么植物？\"}"))
                .thenReturn(llmText("这是一盆健康的绿萝。"));

        ToolCallResponse response = run("这是什么植物？", new AgentContext(
                "golden-user", "golden-conv", null, null,
                Set.of(AgentContext.Permission.AGENT_READ), null, "art-1"));

        verify(artifactService).analyze("art-1", "golden-user", "这是什么植物？");
        assertToolSucceeded(response, "analyzeImage");
        assertTrue(response.getText().contains("绿萝"));
        assertToolResultFedBackToLlm("绿萝");
    }

    @Test
    void leafImageInvokesDiseaseDiagnosisWithoutPersisting() throws Exception {
        DiseaseResult result = new DiseaseResult();
        result.setDiseaseName("白粉病");
        result.setConfidence("HIGH");
        result.setSymptoms("叶面白色粉末状霉层");
        result.setTreatmentPlan("摘除病叶并喷施杀菌剂");
        result.setPrevention("加强通风降低湿度");
        result.setUrgencyLevel("24H");
        when(userSessionService.getPendingImageBase64("golden-user")).thenReturn("aGVsbG8=");
        when(diseaseRecognitionService.diagnose(any(byte[].class), anyString(), anyString()))
                .thenReturn(result);
        when(llmService.chatWithTools(any(), any()))
                .thenReturn(llmToolCall("diagnoseDisease", "{\"type\":\"plant\"}"))
                .thenReturn(llmText("叶片疑似白粉病，建议摘除病叶并喷施杀菌剂。"));

        ToolCallResponse response = run("帮我看看这片叶子是不是生病了");

        verify(diseaseRecognitionService).diagnose(any(byte[].class), eq("plant"), eq("golden-user"));
        // 诊断是纯读：保存诊断必须经 saveDiagnosis 确认卡，不允许顺手落库
        verify(diseaseRecognitionService, org.mockito.Mockito.never())
                .saveHistory(any(), any(), any(), any());
        assertToolSucceeded(response, "diagnoseDisease");
        assertTrue(response.getText().contains("白粉病"));
        assertToolResultFedBackToLlm("白粉病");
    }

    private ToolCallResponse run(String userMessage) {
        return run(userMessage, new AgentContext("golden-user", "golden-conv", null, null));
    }

    private ToolCallResponse run(String userMessage, AgentContext context) {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "你是宠物/植物护理助手");
        messages.add(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userMessage);
        messages.add(user);
        return toolCallingService.chatWithMessages(messages, Set.of(), context);
    }

    private void assertToolSucceeded(ToolCallResponse response, String toolName) {
        assertEquals(1, response.getToolCallHistory().size());
        assertEquals(toolName, response.getToolCallHistory().get(0).getToolName());
        assertTrue(response.getToolCallHistory().get(0).isSuccess(), toolName + " should succeed");
    }

    /** 工具的真实返回必须作为 role=tool 消息进入下一轮 LLM 请求。 */
    private void assertToolResultFedBackToLlm(String expectedFragment) throws Exception {
        ArgumentCaptor<JSONArray> captor = ArgumentCaptor.forClass(JSONArray.class);
        verify(llmService, times(2)).chatWithTools(captor.capture(), any());
        List<JSONArray> calls = captor.getAllValues();
        assertEquals(2, calls.size());
        JSONArray secondRound = calls.get(1);
        JSONObject toolMessage = secondRound.getJSONObject(secondRound.size() - 1);
        assertEquals("tool", toolMessage.getString("role"));
        assertTrue(toolMessage.getString("content").contains(expectedFragment));
    }

    private static JSONObject llmToolCall(String toolName, String argumentsJson) {
        JSONObject function = new JSONObject();
        function.put("name", toolName);
        function.put("arguments", argumentsJson);

        JSONObject toolCall = new JSONObject();
        toolCall.put("id", "call_1");
        toolCall.put("type", "function");
        toolCall.put("function", function);

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("tool_calls", new JSONArray(List.of(toolCall)));
        return responseOf(message);
    }

    private static JSONObject llmText(String text) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", text);
        return responseOf(message);
    }

    private static JSONObject responseOf(JSONObject message) {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);

        JSONObject body = new JSONObject();
        body.put("choices", new JSONArray(List.of(choice)));
        return body;
    }
}
