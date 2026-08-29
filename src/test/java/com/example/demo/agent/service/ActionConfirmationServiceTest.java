package com.example.demo.agent.service;

import com.example.demo.agent.domain.ActionConfirmation;
import com.example.demo.agent.domain.CareEvent;
import com.example.demo.agent.repository.ActionConfirmationRepository;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.ai.AgentContext;
import com.example.demo.ai.ToolBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionConfirmationServiceTest {

    private ActionConfirmationRepository repository;
    private CareEventRepository eventRepository;
    private ToolBroker toolBroker;
    private ActionConfirmationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActionConfirmationRepository.class);
        eventRepository = mock(CareEventRepository.class);
        toolBroker = mock(ToolBroker.class);
        service = new ActionConfirmationService(repository, eventRepository, toolBroker);
    }

    @Test
    void confirmExecutesOnlyAfterExplicitUserActionAndWritesEvent() {
        ActionConfirmation confirmation = pending();
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(confirmation));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolBroker.execute(any(), any(), any(), any())).thenReturn("提醒已创建");

        var result = service.confirm(9L, "user-1");

        assertEquals("EXECUTED", result.status());
        assertEquals("提醒已创建", result.reply());
        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(toolBroker).execute(any(), any(), any(), contextCaptor.capture());
        assertTrue(contextCaptor.getValue().hasPermission(AgentContext.Permission.AGENT_WRITE));
        assertEquals(9L, contextCaptor.getValue().confirmedActionId());

        ArgumentCaptor<CareEvent> eventCaptor = ArgumentCaptor.forClass(CareEvent.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertEquals("PET", eventCaptor.getValue().getSubjectType());
        assertEquals("REMINDER_CREATE", eventCaptor.getValue().getEventType());
        assertEquals("AGENT_CONFIRMATION", eventCaptor.getValue().getSource());
    }

    @Test
    void cancelDoesNotExecuteTool() {
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(pending()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.cancel(9L, "user-1");

        assertEquals("CANCELLED", result.status());
        verify(toolBroker, org.mockito.Mockito.never()).execute(any(), any(), any(), any());
    }

    private ActionConfirmation pending() {
        return ActionConfirmation.builder()
                .id(9L)
                .userId("user-1")
                .conversationId("agent_1")
                .subjectId(12L)
                .toolName("createCareReminder")
                .actionType("REMINDER_CREATE")
                .payloadJson("""
                        {"targetType":"PET","targetId":12,"reminderType":"用药",
                        "content":"给咪咪滴耳药","dueAt":"2026-08-30 08:00"}
                        """)
                .status(ActionConfirmation.Status.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }
}
