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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionConfirmationServiceTest {

    private ActionConfirmationRepository repository;
    private CareEventRepository eventRepository;
    private ToolBroker toolBroker;
    private ActionConfirmationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ActionConfirmationRepository.class);
        eventRepository = mock(CareEventRepository.class);
        toolBroker = mock(ToolBroker.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        service = new ActionConfirmationService(repository, eventRepository, toolBroker, transactionTemplate);
    }

    @Test
    void confirmExecutesOnlyAfterExplicitUserActionAndWritesEvent() {
        when(repository.claimForExecution(eq(9L), eq("user-1"), any(), any(), any())).thenReturn(1);
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(pending()));
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
    void concurrentClaimIsRejectedWithoutExecutingTool() {
        when(repository.claimForExecution(eq(9L), eq("user-1"), any(), any(), any())).thenReturn(0);
        ActionConfirmation executing = pending();
        executing.setStatus(ActionConfirmation.Status.EXECUTING);
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(executing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(9L, "user-1"));

        assertTrue(error.getMessage().contains("正在执行"));
        verify(toolBroker, never()).execute(any(), any(), any(), any());
    }

    @Test
    void failedExecutionMarksFailedAndAllowsRetry() {
        when(repository.claimForExecution(eq(9L), eq("user-1"), any(), any(), any()))
                .thenReturn(1, 1);
        ActionConfirmation failed = pending();
        failed.setStatus(ActionConfirmation.Status.FAILED);
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(pending()))
                .thenReturn(Optional.of(failed))
                .thenReturn(Optional.of(pending()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolBroker.execute(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("工具执行超时", new IllegalStateException("上游超时")))
                .thenReturn("提醒已创建");

        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> service.confirm(9L, "user-1"));
        assertTrue(first.getMessage().contains("重试"));
        verify(repository).markFailed(eq(9L),
                eq(ActionConfirmation.Status.EXECUTING), eq(ActionConfirmation.Status.FAILED),
                eq("上游超时"));

        var retried = service.confirm(9L, "user-1");

        assertEquals("EXECUTED", retried.status());
        assertEquals("提醒已创建", retried.reply());
        verify(eventRepository).save(any(CareEvent.class));
    }

    @Test
    void expiredConfirmationIsRejectedAndMarkedExpired() {
        when(repository.claimForExecution(eq(9L), eq("user-1"), any(), any(), any())).thenReturn(0);
        ActionConfirmation overdue = pending();
        overdue.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(overdue));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(9L, "user-1"));

        assertTrue(error.getMessage().contains("超时"));
        ArgumentCaptor<ActionConfirmation> captor = ArgumentCaptor.forClass(ActionConfirmation.class);
        verify(repository).save(captor.capture());
        assertEquals(ActionConfirmation.Status.EXPIRED, captor.getValue().getStatus());
        verify(toolBroker, never()).execute(any(), any(), any(), any());
    }

    @Test
    void cancelDoesNotExecuteTool() {
        when(repository.cancelPending(eq(9L), eq("user-1"), any(), any())).thenReturn(1);
        ActionConfirmation cancelled = pending();
        cancelled.setStatus(ActionConfirmation.Status.CANCELLED);
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(cancelled));

        var result = service.cancel(9L, "user-1");

        assertEquals("CANCELLED", result.status());
        verify(toolBroker, never()).execute(any(), any(), any(), any());
    }

    @Test
    void cancelAfterFailureIsRejected() {
        when(repository.cancelPending(eq(9L), eq("user-1"), any(), any())).thenReturn(0);
        ActionConfirmation failed = pending();
        failed.setStatus(ActionConfirmation.Status.FAILED);
        when(repository.findByIdAndUserId(9L, "user-1"))
                .thenReturn(Optional.of(failed));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.cancel(9L, "user-1"));

        assertTrue(error.getMessage().contains("执行失败"));
        verify(toolBroker, never()).execute(any(), any(), any(), any());
    }

    @Test
    void confirmationIdParsesSingleAndMultiDigitIds() {
        // 回归：此前 off-by-one 会导致单位数 ID 解析为空（无确认卡）、多位数错位
        assertEquals(9L, service.confirmationId("[CONFIRMATION:9] 该操作需要确认后才会执行。"));
        assertEquals(42L, service.confirmationId("[CONFIRMATION:42] 该操作需要确认后才会执行。"));
        assertEquals(107L, service.confirmationId("前文 [CONFIRMATION:107] 后文"));
    }

    @Test
    void confirmationIdReturnsNullForMissingOrBrokenMarkers() {
        assertNull(service.confirmationId("没有标记"));
        assertNull(service.confirmationId("[CONFIRMATION:abc]"));
        assertNull(service.confirmationId("[CONFIRMATION:"));
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
