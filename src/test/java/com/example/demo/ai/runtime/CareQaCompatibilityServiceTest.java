package com.example.demo.ai.runtime;

import com.example.demo.chat.entity.PetProfile;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareQaCompatibilityServiceTest {

    private AgentRuntimeService runtimeService;
    private PetProfileRepository petProfileRepository;

    @BeforeEach
    void setUp() {
        runtimeService = mock(AgentRuntimeService.class);
        petProfileRepository = mock(PetProfileRepository.class);
    }

    @Test
    void runtimeResultPreservesLegacyQaContract() {
        CareQaCompatibilityService service = new CareQaCompatibilityService(
                runtimeService,
                mock(PlantProfileRepository.class),
                petProfileRepository
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-1");
        PetProfile pet = new PetProfile("咪咪", "猫", null, null);
        pet.setId(12L);
        when(petProfileRepository.findById(12L)).thenReturn(Optional.of(pet));
        when(runtimeService.chat(any(), any())).thenReturn(new AgentChatResponse(
                true, null, "收到", "agent_1", null, null, "trace_1",
                1, 10, null, null, null
        ));

        Map<String, Object> params = new HashMap<>();
        params.put("question", "猫怎么样");
        params.put("targetType", "pet");
        params.put("targetId", 12);
        Map<String, Object> result = service.qa(params, session);

        assertEquals("收到", result.get("reply"));
        assertEquals("咪咪", result.get("targetName"));
        assertEquals("猫", result.get("species"));
        assertEquals("agent_1", result.get("conversationId"));
        assertEquals("trace_1", result.get("traceId"));
    }

    @Test
    void runtimeFailuresPropagateInsteadOfFallingBackToLegacyPath() {
        CareQaCompatibilityService service = new CareQaCompatibilityService(
                runtimeService,
                mock(PlantProfileRepository.class),
                petProfileRepository
        );
        MockHttpSession session = new MockHttpSession();
        when(runtimeService.chat(any(), any()))
                .thenThrow(new AgentAuthenticationException("请先登录"));

        assertThrows(AgentAuthenticationException.class,
                () -> service.qa(Map.of("question", "你好"), session));
        verify(runtimeService).chat(any(), any());
    }
}
