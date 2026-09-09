package com.example.demo.ai.runtime;

import com.example.demo.agent.domain.ActionConfirmation;
import com.example.demo.agent.domain.AgentConversation;
import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.domain.ToolTrace;
import com.example.demo.agent.repository.AgentConversationRepository;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.agent.repository.ToolTraceRepository;
import com.example.demo.agent.service.ActionConfirmationService;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentMemoryService;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.agent.service.SubjectDirectoryService;
import com.example.demo.ai.AgentContext;
import com.example.demo.ai.ToolCallResponse;
import com.example.demo.ai.ToolCallResult;
import com.example.demo.ai.ToolCallingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceTest {

    private ToolCallingService toolCallingService;
    private AgentConversationService conversationService;
    private SubjectDirectoryService subjectDirectory;
    private ActionConfirmationService confirmationService;
    private AgentMemoryService memoryService;
    private AgentConversationRepository conversationRepository;
    private ToolTraceRepository traceRepository;
    private AgentRuntimeService runtimeService;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        toolCallingService = mock(ToolCallingService.class);
        conversationService = mock(AgentConversationService.class);
        subjectDirectory = mock(SubjectDirectoryService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        confirmationService = mock(ActionConfirmationService.class);
        memoryService = mock(AgentMemoryService.class);
        CareEventRepository careEventRepository = mock(CareEventRepository.class);
        conversationRepository = mock(AgentConversationRepository.class);
        traceRepository = mock(ToolTraceRepository.class);
        runtimeService = new AgentRuntimeService(
                toolCallingService,
                conversationService,
                subjectDirectory,
                artifactService,
                confirmationService,
                memoryService,
                careEventRepository,
                conversationRepository,
                traceRepository,
                true,
                10
        );

        session = new MockHttpSession();
        session.setAttribute("user", "user-1");

        AgentConversation conversation = AgentConversation.builder()
                .conversationId("agent_1")
                .userId("user-1")
                .build();
        when(conversationService.resolve(any(), eq("user-1"), any())).thenReturn(conversation);
        when(conversationService.history("agent_1", "user-1", 10)).thenReturn(List.of());
        when(memoryService.retrieve(any(), eq("user-1"), eq("agent_1"))).thenReturn(List.of());
        when(careEventRepository.findByUserIdAndSubjectIdOrderByOccurredAtDesc(
                eq("user-1"), any())).thenReturn(List.of());
    }

    @Test
    void chatBuildsOwnedContextAndStoresAgentMessages() {
        CareSubject subject = CareSubject.builder()
                .id(12L)
                .userId("user-1")
                .subjectType(CareSubject.SubjectType.PET)
                .name("咪咪")
                .species("猫")
                .sourceType(CareSubject.SourceType.CARE_TARGET)
                .sourceId(12L)
                .active(true)
                .build();
        when(subjectDirectory.resolve("user-1", "PET", 12L))
                .thenReturn(Optional.of(new SubjectDirectoryService.ResolvedSubject(subject, 12L)));
        when(toolCallingService.chatWithMessagesStream(any(), anySet(), any(), any())).thenReturn(
                ToolCallResponse.builder()
                        .text("收到")
                        .traceId("trace-1")
                        .toolCallHistory(List.of())
                        .build()
        );

        AgentChatResponse response = runtimeService.chat(
                new AgentChatRequest(null, "我家猫怎么样", null, null,
                        "PET", 12L, null),
                session
        );

        ArgumentCaptor<AgentContext> captor = ArgumentCaptor.forClass(AgentContext.class);
        verify(toolCallingService).chatWithMessagesStream(any(), anySet(), captor.capture(), any());
        assertEquals("user-1", captor.getValue().userId());
        assertEquals("PET", captor.getValue().subjectType());
        assertEquals(12L, captor.getValue().subjectId());
        assertEquals("收到", response.reply());
        verify(conversationService).append("agent_1", "user-1",
                "user", "我家猫怎么样", null, "trace-1");
        verify(conversationService).append("agent_1", "user-1",
                "assistant", "收到", null, "trace-1");
    }

    @Test
    void chatConvertsMarkerIntoConfirmationCard() {
        ToolCallResult toolResult = ToolCallResult.success(
                "trace-1", "createCareReminder", null,
                "[CONFIRMATION:9] 该操作需要确认", 1
        );
        ActionConfirmation confirmation = ActionConfirmation.builder()
                .id(9L)
                .userId("user-1")
                .toolName("createCareReminder")
                .actionType("REMINDER_CREATE")
                .status(ActionConfirmation.Status.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(confirmationService.confirmationId(toolResult.getResult())).thenReturn(9L);
        when(confirmationService.owned(9L, "user-1")).thenReturn(confirmation);
        when(confirmationService.card(confirmation)).thenReturn(new ActionConfirmationService.Card(
                "REMINDER_CONFIRM", "确认创建护理提醒", new com.alibaba.fastjson2.JSONObject()
        ));
        when(toolCallingService.chatWithMessagesStream(any(), anySet(), any(), any())).thenReturn(
                ToolCallResponse.builder()
                        .text("请确认 [CONFIRMATION:9]")
                        .traceId("trace-1")
                        .toolCallHistory(List.of(toolResult))
                        .build()
        );

        AgentChatResponse response = runtimeService.chat(
                new AgentChatRequest(null, "明早提醒喂药", null, null, null, null, null),
                session
        );

        assertEquals("请确认", response.reply());
        assertEquals(1, response.cards().size());
        assertEquals("REMINDER_CONFIRM", response.cards().get(0).type());
    }

    @Test
    void chatRequiresAuthentication() {
        MockHttpSession anonymousSession = new MockHttpSession();

        assertThrows(AgentAuthenticationException.class, () -> runtimeService.chat(
                new AgentChatRequest(null, "你好", null, null, null, null, null),
                anonymousSession
        ));
    }

    @Test
    void tracesExposeOwnedConversationHistoryOnly() {
        when(conversationRepository.findByConversationIdAndUserId("agent_1", "user-1"))
                .thenReturn(Optional.of(AgentConversation.builder()
                        .conversationId("agent_1").userId("user-1").build()));
        when(traceRepository.findByConversationIdOrderByCreatedAtDesc("agent_1"))
                .thenReturn(List.of(ToolTrace.builder()
                        .traceId("trace-1").conversationId("agent_1").userId("user-1")
                        .toolName("getWeather").accessMode("READ").status("SUCCESS")
                        .durationMs(12).argumentsJson("{\"city\":\"北京\"}").resultJson("晴").build()));

        List<Map<String, Object>> traces = runtimeService.traces("agent_1", session);

        assertEquals(1, traces.size());
        assertEquals("getWeather", traces.get(0).get("toolName"));
        assertEquals("SUCCESS", traces.get(0).get("status"));

        when(conversationRepository.findByConversationIdAndUserId("agent_other", "user-1"))
                .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> runtimeService.traces("agent_other", session));
    }

    @Test
    void chatRejectsDisabledRuntime() {
        AgentRuntimeService disabled = new AgentRuntimeService(
                toolCallingService, conversationService, subjectDirectory, mock(ArtifactService.class),
                confirmationService, memoryService, mock(CareEventRepository.class),
                conversationRepository, traceRepository, false, 10
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> disabled.chat(
                new AgentChatRequest(null, "你好", null, null, null, null, null),
                session
        ));
        assertEquals("Agent Runtime 未开启，请设置 agent.runtime.enabled=true 后重试", error.getMessage());
    }
}
