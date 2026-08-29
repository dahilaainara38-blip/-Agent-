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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
                petProfileRepository,
                true
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
        Optional<Map<String, Object>> result = service.qa(params, session);

        assertTrue(result.isPresent());
        assertEquals("收到", result.get().get("reply"));
        assertEquals("咪咪", result.get().get("targetName"));
        assertEquals("猫", result.get().get("species"));
        assertEquals("agent_1", result.get().get("conversationId"));
        assertEquals("trace_1", result.get().get("traceId"));
    }

    @Test
    void disabledRuntimeKeepsLegacyPath() {
        CareQaCompatibilityService service = new CareQaCompatibilityService(
                runtimeService,
                mock(PlantProfileRepository.class),
                petProfileRepository,
                false
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-1");

        assertEquals(Optional.empty(), service.qa(Map.of("question", "你好"), session));
        verifyNoInteractions(runtimeService);
    }
}
