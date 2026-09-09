package com.example.demo.care;

import com.example.demo.agent.service.CareEventRecorder;
import com.example.demo.aicare.Result;
import com.example.demo.chat.entity.CareRecord;
import com.example.demo.chat.entity.PlantProfile;
import com.example.demo.chat.entity.PetProfile;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.vision.VisionService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/care")
public class CareController {

    private static final Logger logger = LoggerFactory.getLogger(CareController.class);

    private final VisionService visionService;
    private final PlantProfileRepository plantProfileRepository;
    private final PetProfileRepository petProfileRepository;
    private final LegacyCareRecordRepository careRecordRepository;
    private final CareReminderService careReminderService;
    private final CareEventRecorder careEventRecorder;

    public CareController(VisionService visionService,
                          PlantProfileRepository plantProfileRepository,
                          PetProfileRepository petProfileRepository,
                          LegacyCareRecordRepository careRecordRepository,
                          CareReminderService careReminderService,
                          CareEventRecorder careEventRecorder) {
        this.visionService = visionService;
        this.plantProfileRepository = plantProfileRepository;
        this.petProfileRepository = petProfileRepository;
        this.careRecordRepository = careRecordRepository;
        this.careReminderService = careReminderService;
        this.careEventRecorder = careEventRecorder;
    }

    private String currentUser(HttpSession session) {
        String userName = (String) session.getAttribute("user");
        return userName == null || userName.isBlank() ? null : userName;
    }

    @PostMapping("/identify")
    public Result<Map<String, Object>> identify(@RequestBody Map<String, String> params,
                                                HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        String type = params.get("type");
        String imageBase64 = params.get("image");

        logger.info("Care identify request, type: {}", type);

        try {
            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
            
            String prompt = type.equals("PLANT") 
                ? "请识别图片中的植物种类，并以详细的文本描述，包括品种名称、外观特征、生长状态等信息。"
                : "请识别图片中的宠物种类，并以详细的文本描述，包括品种名称、外观特征、健康状态观察等信息。";

            String analysis = visionService.analyzeImageWithCustomPrompt(imageBytes, prompt);

            Map<String, Object> result = new HashMap<>();
            result.put("result", analysis);
            
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Care identify failed", e);
            return Result.error("识别失败：" + e.getMessage());
        }
    }

    @GetMapping("/targets/{type}")
    public Result<List<Object>> getTargets(@PathVariable String type, HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }

        List<Object> targets = new ArrayList<>();
        if ("PLANT".equalsIgnoreCase(type)) {
            for (PlantProfile plant : plantProfileRepository.findAll()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", plant.getId());
                map.put("name", plant.getName());
                map.put("species", plant.getSpecies());
                map.put("createTime", plant.getCreateTime());
                targets.add(map);
            }
        } else if ("PET".equalsIgnoreCase(type)) {
            for (PetProfile pet : petProfileRepository.findAll()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", pet.getId());
                map.put("name", pet.getName());
                map.put("species", pet.getSpecies());
                map.put("createTime", pet.getCreateTime());
                targets.add(map);
            }
        }

        return Result.success(targets);
    }

    @PostMapping("/targets/{type}")
    public Result<String> createTarget(@PathVariable String type, @RequestBody Map<String, Object> params,
                                       HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        String name = (String) params.get("name");
        String species = (String) params.get("species");

        if ("PLANT".equalsIgnoreCase(type)) {
            PlantProfile plant = plantProfileRepository.save(new PlantProfile(name, species, null, null));
            recordTargetEvent(user, "PLANT", plant.getId(), "TARGET_CREATE", name, species);
        } else if ("PET".equalsIgnoreCase(type)) {
            PetProfile pet = petProfileRepository.save(new PetProfile(name, species, null, null));
            recordTargetEvent(user, "PET", pet.getId(), "TARGET_CREATE", name, species);
        }

        return Result.success("保存成功");
    }

    @DeleteMapping("/targets/{type}/{id}")
    public Result<String> deleteTarget(@PathVariable String type, @PathVariable Long id,
                                       HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        String subjectType = "PLANT".equalsIgnoreCase(type) ? "PLANT" : "PET";

        if ("PLANT".equalsIgnoreCase(type)) {
            plantProfileRepository.deleteById(id);
            careRecordRepository.deleteByTargetTypeAndTargetId("PLANT", id);
        } else if ("PET".equalsIgnoreCase(type)) {
            petProfileRepository.deleteById(id);
            careRecordRepository.deleteByTargetTypeAndTargetId("PET", id);
        }

        careEventRecorder.record(user, subjectType, id, "TARGET_DELETE",
                Map.of("targetType", subjectType, "targetId", id),
                "REST", "rest_target_delete_" + subjectType.toLowerCase() + "_" + id);
        return Result.success("删除成功");
    }

    @GetMapping("/records/{type}/{targetId}")
    public Result<List<Map<String, Object>>> getRecords(@PathVariable String type, @PathVariable Long targetId,
                                                        HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        List<CareRecord> records = careRecordRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(type, targetId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (CareRecord record : records) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", record.getId());
            map.put("recordType", record.getRecordType());
            map.put("content", record.getContent());
            map.put("createdAt", record.getCreatedAt());
            result.add(map);
        }

        return Result.success(result);
    }

    @PostMapping("/records")
    public Result<String> createRecord(@RequestBody Map<String, Object> params, HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        String targetType = (String) params.get("targetType");
        Long targetId = ((Number) params.get("targetId")).longValue();
        String recordType = (String) params.get("recordType");
        String content = (String) params.get("content");

        CareRecord record = careRecordRepository.save(new CareRecord(targetType, targetId, recordType, content));
        careEventRecorder.record(user, targetType.toUpperCase(), targetId, "CARE_RECORD_SAVE",
                Map.of("targetType", targetType, "targetId", targetId,
                        "recordType", recordType, "content", content),
                "REST", "rest_record_" + record.getId());

        return Result.success("记录保存成功");
    }

    private void recordTargetEvent(String user, String subjectType, Long targetId,
                                   String eventType, String name, String species) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetType", subjectType);
        payload.put("targetId", targetId);
        payload.put("name", name);
        payload.put("species", species);
        careEventRecorder.record(user, subjectType, targetId, eventType, payload,
                "REST", "rest_target_create_" + subjectType.toLowerCase() + "_" + targetId);
    }

    /**
     * 待办提醒计数（首页数据卡）。旧 /api/ai/* 前缀端点已随 AiController 下线。
     */
    @GetMapping("/reminders/pending")
    public Result<Map<String, Object>> pendingReminders(HttpSession session) {
        String user = currentUser(session);
        if (user == null) {
            return Result.error("未登录");
        }
        return Result.success(Map.of("count", careReminderService.countPendingReminders(user)));
    }
}
