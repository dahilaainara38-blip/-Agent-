package com.example.demo.ai;

import com.example.demo.care.service.CareReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 小程序专用端点已下线；聊天/工具链路统一由 Agent Runtime（/api/agent）承载。
 * 本控制器仅保留旧首页仍在调用的待办提醒计数。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final CareReminderService careReminderService;

    @GetMapping("/care/reminders/pending")
    public ResponseEntity<Map<String, Object>> pendingReminderCount(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        Map<String, Object> response = new java.util.HashMap<>();
        if (userId == null) {
            response.put("success", false);
            response.put("error", "未登录");
            return ResponseEntity.ok(response);
        }
        try {
            int count = careReminderService.countPendingReminders(userId);
            response.put("success", true);
            response.put("count", count);
        } catch (Exception e) {
            log.error("Failed to count pending reminders", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
}
