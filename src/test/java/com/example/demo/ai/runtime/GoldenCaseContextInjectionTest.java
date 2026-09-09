package com.example.demo.ai.runtime;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.domain.AgentConversation;
import com.example.demo.agent.domain.CareEvent;
import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentMemoryService;
import com.example.demo.agent.service.SubjectDirectoryService;
import com.example.demo.chat.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 黄金用例 5：带 target 的护理问题必须把选中档案与最近护理事件注入 system prompt。
 * 全链路走真实 AgentRuntimeService，仅 mock 其仓储/服务依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoldenCaseContextInjectionTest {

    @Autowired
    private AgentRuntimeService runtimeService;

    @MockitoBean
    private LlmService llmService;

    @MockitoBean
    private SubjectDirectoryService subjectDirectory;

    @MockitoBean
    private AgentConversationService conversationService;

    @MockitoBean
    private AgentMemoryService memoryService;

    @MockitoBean
    private CareEventRepository careEventRepository;

    @Test
    void careQuestionInjectsSelectedSubjectAndRecentEventsIntoPrompt() throws Exception {
        CareSubject subject = CareSubject.builder()
                .userId("golden-user")
                .subjectType(CareSubject.SubjectType.PET)
                .name("咪咪")
                .species("英短")
                .breed("银渐层")
                .sourceType(CareSubject.SourceType.AGENT)
                .active(true)
                .build();
        when(subjectDirectory.resolve("golden-user", "PET", 12L))
                .thenReturn(java.util.Optional.of(new SubjectDirectoryService.ResolvedSubject(subject, 12L)));

        AgentConversation conversation = AgentConversation.builder()
                .conversationId("agent_gc5")
                .userId("golden-user")
                .currentSubjectId(12L)
                .build();
        when(conversationService.resolve(any(), anyString(), any())).thenReturn(conversation);
        when(conversationService.history("agent_gc5", "golden-user", 10)).thenReturn(List.of());
        when(conversationService.append(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> com.example.demo.agent.domain.AgentMessage.builder().build());

        when(memoryService.retrieve(any(), any(), any())).thenReturn(List.of());

        when(careEventRepository.findByUserIdAndSubjectIdOrderByOccurredAtDesc("golden-user", 12L))
                .thenReturn(List.of(CareEvent.builder()
                        .eventId("evt_gc5")
                        .userId("golden-user")
                        .subjectType("PET")
                        .subjectId(12L)
                        .eventType("REMINDER_DELIVERED")
                        .payload("{\"reminderType\":\"用药\",\"content\":\"给咪咪滴耳药\"}")
                        .source("SCHEDULER")
                        .occurredAt(LocalDateTime.now())
                        .build()));

        when(llmService.chatWithTools(any(), any())).thenReturn(llmText("咪咪整体状态良好，记得按时滴耳药。"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "golden-user");

        AgentChatResponse response = runtimeService.chat(
                new AgentChatRequest(null, "咪咪最近怎么样？", null, null, "PET", 12L, null), session);

        assertEquals("咪咪整体状态良好，记得按时滴耳药。", response.reply());
        assertEquals("agent_gc5", response.conversationId());

        // 第一轮 LLM 请求的 system prompt 必须包含选中档案与最近事件
        ArgumentCaptor<JSONArray> captor = ArgumentCaptor.forClass(JSONArray.class);
        verify(llmService).chatWithTools(captor.capture(), any());
        String systemPrompt = captor.getValue().getJSONObject(0).getString("content");
        assertTrue(systemPrompt.contains("咪咪"), "system prompt should contain subject name");
        assertTrue(systemPrompt.contains("英短"), "system prompt should contain species");
        assertTrue(systemPrompt.contains("REMINDER_DELIVERED"), "system prompt should contain recent event");
        assertTrue(systemPrompt.contains("滴耳药"), "system prompt should contain event payload");
    }

    private static JSONObject llmText(String text) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", text);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        JSONObject body = new JSONObject();
        body.put("choices", new JSONArray(List.of(choice)));
        return body;
    }
}
