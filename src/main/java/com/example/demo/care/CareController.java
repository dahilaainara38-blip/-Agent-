package com.example.demo.care;

import com.example.demo.aicare.Result;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.vision.VisionService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 旧护理页的兼容端点。档案与护理记录已归并到 agent 域（/api/agent/subjects*），
 * 本控制器仅保留纯视觉识别（care.html 识别建档流）与首页待办计数。
 */
@RestController
@RequestMapping("/api/care")
public class CareController {

    private static final Logger logger = LoggerFactory.getLogger(CareController.class);

    private final VisionService visionService;
    private final CareReminderService careReminderService;

    public CareController(VisionService visionService,
                          CareReminderService careReminderService) {
        this.visionService = visionService;
        this.careReminderService = careReminderService;
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
