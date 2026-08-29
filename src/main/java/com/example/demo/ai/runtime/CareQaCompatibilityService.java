package com.example.demo.ai.runtime;

import com.example.demo.chat.entity.PetProfile;
import com.example.demo.chat.entity.PlantProfile;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 旧 /api/care/qa 契约的兼容适配层：统一转发到 Agent Runtime，
 * 并补齐旧前端依赖的 targetName/species 字段。
 */
@Service
public class CareQaCompatibilityService {

    private final AgentRuntimeService runtimeService;
    private final PlantProfileRepository plantProfileRepository;
    private final PetProfileRepository petProfileRepository;

    public CareQaCompatibilityService(AgentRuntimeService runtimeService,
                                       PlantProfileRepository plantProfileRepository,
                                       PetProfileRepository petProfileRepository) {
        this.runtimeService = runtimeService;
        this.plantProfileRepository = plantProfileRepository;
        this.petProfileRepository = petProfileRepository;
    }

    public Map<String, Object> qa(Map<String, Object> params, HttpSession session) {
        String targetType = normalizeType(params.get("targetType"));
        Long targetId = params.get("targetId") instanceof Number number ? number.longValue() : null;
        String image = params.get("image") instanceof String value ? value : null;
        String question = params.get("question") instanceof String value ? value : null;

        AgentChatResponse response = runtimeService.chat(new AgentChatRequest(
                null,
                question,
                null,
                image,
                targetType,
                targetId,
                Set.of()
        ), session);

        Map<String, Object> result = new HashMap<>();
        result.put("reply", response.reply());
        result.put("targetName", "");
        result.put("species", "");
        result.put("conversationId", response.conversationId());
        result.put("traceId", response.traceId());
        result.put("toolCallHistory", response.toolCallHistory());
        result.put("cards", response.cards());
        result.put("generatedFiles", response.generatedFiles());

        if (targetId != null && targetType != null) {
            if ("PLANT".equals(targetType)) {
                plantProfileRepository.findById(targetId).ifPresent(plant -> {
                    result.put("targetName", plant.getName());
                    result.put("species", plant.getSpecies());
                });
            } else if ("PET".equals(targetType)) {
                petProfileRepository.findById(targetId).ifPresent(pet -> {
                    result.put("targetName", pet.getName());
                    result.put("species", pet.getSpecies());
                });
            }
        }
        return result;
    }

    private String normalizeType(Object value) {
        if (!(value instanceof String type) || type.isBlank()) {
            return null;
        }
        return type.trim().toUpperCase();
    }
}
