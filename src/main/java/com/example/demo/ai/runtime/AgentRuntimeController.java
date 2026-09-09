package com.example.demo.ai.runtime;

import com.example.demo.agent.domain.Artifact;
import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.service.ActionConfirmationService;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.ai.ToolBroker;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    public AgentRuntimeController(AgentRuntimeService agentRuntimeService,
                                  ArtifactService artifactService,
                                  ActionConfirmationService confirmationService,
                                  ToolBroker toolBroker,
                                  AgentConversationService conversationService) {
        this.agentRuntimeService = agentRuntimeService;
        this.artifactService = artifactService;
        this.confirmationService = confirmationService;
        this.toolBroker = toolBroker;
        this.conversationService = conversationService;
    }

    @PostMapping("/messages")
    public ResponseEntity<AgentChatResponse> message(@RequestBody AgentChatRequest request,
                                                     HttpSession session) {
        return ResponseEntity.ok(agentRuntimeService.chat(request, session));
    }

    @GetMapping("/subjects")
    public ResponseEntity<List<Map<String, Object>>> subjects(HttpSession session) {
        return ResponseEntity.ok(agentRuntimeService.subjects(session).stream()
                .map(this::subjectItem)
                .toList());
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
