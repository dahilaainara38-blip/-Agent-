package com.example.demo.ai.runtime;

import com.example.demo.agent.domain.Artifact;
import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.service.ActionConfirmationService;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.agent.service.CareEventRecorder;
import com.example.demo.agent.service.SubjectDirectoryService;
import com.example.demo.ai.ToolBroker;
import com.example.demo.ai.ToolCallResult;
import com.example.demo.ai.ToolCallingService;
import com.example.demo.care.model.CareRecord;
import com.example.demo.care.service.CareRecordService;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentRuntimeController {

    private final AgentRuntimeService agentRuntimeService;
    private final ArtifactService artifactService;
    private final ActionConfirmationService confirmationService;
    private final ToolBroker toolBroker;
    private final AgentConversationService conversationService;
    private final SubjectDirectoryService subjectDirectory;
    private final CareRecordService careRecordService;
    private final CareEventRecorder careEventRecorder;

    public AgentRuntimeController(AgentRuntimeService agentRuntimeService,
                                  ArtifactService artifactService,
                                  ActionConfirmationService confirmationService,
                                  ToolBroker toolBroker,
                                  AgentConversationService conversationService,
                                  SubjectDirectoryService subjectDirectory,
                                  CareRecordService careRecordService,
                                  CareEventRecorder careEventRecorder) {
        this.agentRuntimeService = agentRuntimeService;
        this.artifactService = artifactService;
        this.confirmationService = confirmationService;
        this.toolBroker = toolBroker;
        this.conversationService = conversationService;
        this.subjectDirectory = subjectDirectory;
        this.careRecordService = careRecordService;
        this.careEventRecorder = careEventRecorder;
    }

    @PostMapping("/messages")
    public ResponseEntity<AgentChatResponse> message(@RequestBody AgentChatRequest request,
                                                     HttpSession session) {
        return ResponseEntity.ok(agentRuntimeService.chat(request, session));
    }

    /**
     * 流式消息端点（SSE）。事件协议：
     * phase（thinking/tools 阶段变化）、delta（回复文本增量，打字机直出）、
     * tool（单个工具执行结果）、done（完整 AgentChatResponse）、error。
     * 鉴权在请求线程同步完成；同步端点 /api/agent/messages 保留为回滚路径。
     */
    @PostMapping("/messages/stream")
    public SseEmitter messageStream(@RequestBody AgentChatRequest request, HttpSession session) {
        String userId = currentUser(session);
        SseEmitter emitter = new SseEmitter(180_000L);

        Thread worker = new Thread(() -> {
            try {
                AgentChatResponse response = agentRuntimeService.chat(request, session,
                        new ToolCallingService.StreamListener() {
                            @Override
                            public void onPhase(String phase, String detail) {
                                send(emitter, "phase", Map.of(
                                        "phase", phase, "detail", detail == null ? "" : detail));
                            }

                            @Override
                            public void onDelta(String text) {
                                send(emitter, "delta", Map.of("text", text));
                            }

                            @Override
                            public void onToolResult(ToolCallResult result) {
                                send(emitter, "tool", Map.of(
                                        "toolName", result.getToolName(),
                                        "success", result.isSuccess(),
                                        "durationMs", result.getDurationMs(),
                                        "arguments", result.getArguments() == null
                                                ? Map.of() : result.getArguments(),
                                        "result", result.getResult() == null ? "" : result.getResult(),
                                        "errorMessage", result.getErrorMessage() == null
                                                ? "" : result.getErrorMessage()));
                            }
                        });
                send(emitter, "done", response);
                emitter.complete();
            } catch (Exception e) {
                try {
                    send(emitter, "error", Map.of("error",
                            e.getMessage() == null ? "Agent 处理失败" : e.getMessage()));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }
        }, "agent-sse-" + userId);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端断开时终止整个流式任务，避免继续烧 LLM/工具调用
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    @GetMapping("/subjects")
    public ResponseEntity<List<Map<String, Object>>> subjects(HttpSession session) {
        return ResponseEntity.ok(agentRuntimeService.subjects(session).stream()
                .map(this::subjectItem)
                .toList());
    }

    @PostMapping("/subjects")
    public ResponseEntity<Map<String, Object>> createSubject(@RequestBody Map<String, String> body,
                                                             HttpSession session) {
        String userId = currentUser(session);
        CareSubject subject = subjectDirectory.create(userId,
                body.get("subjectType"), body.get("name"),
                body.get("species"), body.get("breed"), body.get("profile"));
        careEventRecorder.record(userId, subject.getSubjectType().name(), subject.getId(),
                "SUBJECT_CREATE",
                Map.of("name", subject.getName(),
                        "species", subject.getSpecies() == null ? "" : subject.getSpecies()),
                "REST", "rest_subject_create_" + subject.getId());
        return ResponseEntity.ok(subjectItem(subject));
    }

    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Map<String, Object>> deleteSubject(@PathVariable Long id, HttpSession session) {
        String userId = currentUser(session);
        CareSubject subject = subjectDirectory.softDelete(userId, id);
        careEventRecorder.record(userId, subject.getSubjectType().name(), subject.getId(),
                "SUBJECT_DELETE", Map.of("name", subject.getName()),
                "REST", "rest_subject_delete_" + subject.getId());
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @GetMapping("/subjects/{id}/records")
    public ResponseEntity<List<Map<String, Object>>> subjectRecords(@PathVariable Long id,
                                                                    HttpSession session) {
        String userId = currentUser(session);
        SubjectDirectoryService.ResolvedSubject resolved = subjectDirectory
                .resolve(userId, null, id)
                .orElseThrow(() -> new IllegalArgumentException("档案不存在或不属于当前用户"));
        return ResponseEntity.ok(careRecordService
                .getRecordsByTarget(userId, resolved.effectiveSubjectId()).stream()
                .limit(20)
                .map(record -> Map.<String, Object>of(
                        "id", record.getId(),
                        "recordType", record.getRecordType().name(),
                        "title", record.getTitle() == null ? "" : record.getTitle(),
                        "content", record.getContent() == null ? "" : record.getContent(),
                        "createdAt", record.getCreatedAt() == null ? "" : record.getCreatedAt().toString()
                ))
                .toList());
    }

    /** care.html「新增记录」按钮的显式写路径（对话内记录走 saveCareRecord 确认卡）。 */
    @PostMapping("/subjects/{id}/records")
    public ResponseEntity<Map<String, Object>> addSubjectRecord(@PathVariable Long id,
                                                                @RequestBody Map<String, String> body,
                                                                HttpSession session) {
        String userId = currentUser(session);
        SubjectDirectoryService.ResolvedSubject resolved = subjectDirectory
                .resolve(userId, null, id)
                .orElseThrow(() -> new IllegalArgumentException("档案不存在或不属于当前用户"));
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("记录内容不能为空");
        }
        CareSubject subject = resolved.subject();
        CareRecord.RecordType recordType = parseRecordType(body.get("recordType"));
        CareRecord record = careRecordService.createRecord(CareRecord.builder()
                .userId(userId)
                .targetType(subject.getSubjectType() == CareSubject.SubjectType.PET
                        ? CareRecord.TargetType.PET : CareRecord.TargetType.PLANT)
                .targetId(resolved.effectiveSubjectId())
                .recordType(recordType)
                .content(content.trim())
                .build());
        careEventRecorder.record(userId, subject.getSubjectType().name(), resolved.effectiveSubjectId(),
                "CARE_RECORD_SAVE",
                Map.of("recordType", recordType.name(), "content", content.trim()),
                "REST", "rest_agent_record_" + record.getId());
        return ResponseEntity.ok(Map.of(
                "id", record.getId(),
                "recordType", record.getRecordType().name(),
                "content", record.getContent(),
                "createdAt", record.getCreatedAt() == null ? "" : record.getCreatedAt().toString()
        ));
    }

    private CareRecord.RecordType parseRecordType(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return CareRecord.RecordType.CARE;
        }
        try {
            return CareRecord.RecordType.valueOf(recordType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CareRecord.RecordType.CARE;
        }
    }

    @PostMapping("/conversations/new")
    public ResponseEntity<Map<String, Object>> newConversation(HttpSession session) {
        return ResponseEntity.ok(Map.of("conversationId", agentRuntimeService.newConversation(session)));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> conversations(HttpSession session) {
        String userId = currentUser(session);
        return ResponseEntity.ok(conversationService.recent(userId).stream()
                .map(conversation -> Map.<String, Object>of(
                        "conversationId", conversation.getConversationId(),
                        "summary", conversation.getSummary() == null ? "" : conversation.getSummary(),
                        "subjectId", conversation.getCurrentSubjectId() == null
                                ? 0L : conversation.getCurrentSubjectId(),
                        "status", conversation.getStatus(),
                        "updatedAt", conversation.getUpdatedAt().toString()
                ))
                .toList());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<List<Map<String, Object>>> conversationMessages(
            @PathVariable String conversationId, HttpSession session) {
        String userId = currentUser(session);
        return ResponseEntity.ok(conversationService.history(conversationId, userId, 200).stream()
                .map(message -> Map.<String, Object>of(
                        "messageId", message.getMessageId(),
                        "role", message.getRole(),
                        "content", message.getContent(),
                        "artifactId", message.getArtifactId() == null ? "" : message.getArtifactId(),
                        "createdAt", message.getCreatedAt().toString()
                ))
                .toList());
    }

    @GetMapping("/tools")
    public ResponseEntity<List<ToolBroker.ToolMetadata>> tools() {
        return ResponseEntity.ok(toolBroker.metadata());
    }

    @GetMapping("/traces/{conversationId}")
    public ResponseEntity<List<Map<String, Object>>> traces(@PathVariable String conversationId,
                                                            HttpSession session) {
        return ResponseEntity.ok(agentRuntimeService.traces(conversationId, session));
    }

    @PostMapping("/artifacts")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      HttpSession session) {
        String userId = currentUser(session);
        Artifact artifact = artifactService.storeMultipart(userId, file);
        return ResponseEntity.ok(Map.of(
                "artifactId", artifact.getId(),
                "url", "/api/agent/artifacts/" + artifact.getId() + "/content",
                "mimeType", artifact.getMimeType(),
                "byteSize", artifact.getByteSize()
        ));
    }

    @GetMapping("/artifacts/{id}/content")
    public ResponseEntity<FileSystemResource> content(@PathVariable String id, HttpSession session) {
        String userId = currentUser(session);
        Artifact artifact = artifactService.findOwned(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("图片不存在或不属于当前用户"));
        FileSystemResource resource = new FileSystemResource(Path.of(artifact.getStoragePath()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + artifact.getId() + "\"")
                .body(resource);
    }

    @PostMapping("/confirmations/{id}/confirm")
    public ResponseEntity<ActionConfirmationService.ConfirmationOutcome> confirm(
            @PathVariable Long id, HttpSession session) {
        return ResponseEntity.ok(confirmationService.confirm(id, currentUser(session)));
    }

    @PostMapping("/confirmations/{id}/cancel")
    public ResponseEntity<ActionConfirmationService.ConfirmationOutcome> cancel(
            @PathVariable Long id, HttpSession session) {
        return ResponseEntity.ok(confirmationService.cancel(id, currentUser(session)));
    }

    private Map<String, Object> subjectItem(CareSubject subject) {
        return Map.of(
                "id", subject.getId(),
                "type", subject.getSubjectType().name(),
                "name", subject.getName(),
                "species", subject.getSpecies() == null ? "" : subject.getSpecies(),
                "breed", subject.getBreed() == null ? "" : subject.getBreed(),
                "sourceId", subject.getSourceId() == null ? subject.getId() : subject.getSourceId()
        );
    }

    private String currentUser(HttpSession session) {
        String userId = session == null ? null : (String) session.getAttribute("user");
        if (userId == null || userId.isBlank()) {
            throw new AgentAuthenticationException("请先登录");
        }
        return userId;
    }

    @ExceptionHandler(AgentAuthenticationException.class)
    public ResponseEntity<AgentChatResponse> unauthorized(AgentAuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AgentChatResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentChatResponse> disabled(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(AgentChatResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentChatResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(AgentChatResponse.error(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentChatResponse> unavailable(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentChatResponse.error("Agent 处理失败：" + e.getMessage()));
    }
}
