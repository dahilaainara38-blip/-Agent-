package com.example.demo.ai.runtime;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.domain.AgentMessage;
import com.example.demo.agent.domain.Artifact;
import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.repository.CareEventRepository;
import com.example.demo.agent.service.ActionConfirmationService;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentMemoryService;
import com.example.demo.agent.service.ArtifactService;
import com.example.demo.agent.service.SubjectDirectoryService;
import com.example.demo.ai.AgentContext;
import com.example.demo.ai.ToolCallResponse;
import com.example.demo.ai.ToolCallingService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class AgentRuntimeService {

    private final ToolCallingService toolCallingService;
    private final AgentConversationService conversationService;
    private final SubjectDirectoryService subjectDirectory;
    private final ArtifactService artifactService;
    private final ActionConfirmationService confirmationService;
    private final AgentMemoryService memoryService;
    private final CareEventRepository careEventRepository;
    private final boolean runtimeEnabled;
    private final int historyLimit;

    public AgentRuntimeService(ToolCallingService toolCallingService,
                               AgentConversationService conversationService,
                               SubjectDirectoryService subjectDirectory,
                               ArtifactService artifactService,
                               ActionConfirmationService confirmationService,
                               AgentMemoryService memoryService,
                               CareEventRepository careEventRepository,
                               @Value("${agent.runtime.enabled:false}") boolean runtimeEnabled,
                               @Value("${agent.memory.max-messages:10}") int historyLimit) {
        this.toolCallingService = toolCallingService;
        this.conversationService = conversationService;
        this.subjectDirectory = subjectDirectory;
        this.artifactService = artifactService;
        this.confirmationService = confirmationService;
        this.memoryService = memoryService;
        this.careEventRepository = careEventRepository;
        this.runtimeEnabled = runtimeEnabled;
        this.historyLimit = historyLimit;
    }

    public AgentChatResponse chat(AgentChatRequest request, HttpSession session) {
        requireEnabled();
        String userId = currentUser(session);
        String message = resolveMessage(request);
        SubjectDirectoryService.ResolvedSubject subject = subjectDirectory
                .resolve(userId, request.subjectType(), request.subjectId())
                .orElse(null);

        Artifact artifact = resolveArtifact(userId, request);
        String conversationId = resolveConversationId(request, session, userId,
                subject == null ? null : subject.effectiveSubjectId());
        AgentContext context = new AgentContext(
                userId,
                conversationId,
                subject == null ? null : subject.subject().getSubjectType().name(),
                subject == null ? null : subject.effectiveSubjectId(),
                Set.of(AgentContext.Permission.AGENT_READ),
                null,
                artifact == null ? null : artifact.getId()
        );

        List<AgentMessage> history = conversationService.history(
                conversationId, userId, historyLimit);
        List<String> retrieval = memoryService.retrieve(message, userId, conversationId);
        ToolCallResponse response = toolCallingService.chatWithMessages(
                promptMessages(history, systemPrompt(userId, subject, artifact, retrieval), message),
                allowedTools(request),
                context
        );

        conversationService.append(conversationId, userId, "user", message,
                artifact == null ? null : artifact.getId(), response.getTraceId());
        conversationService.append(conversationId, userId, "assistant", response.getText(),
                null, response.getTraceId());
        memoryService.remember(conversationId, userId, message, response.getText());

        return new AgentChatResponse(
                true,
                null,
                cleanReply(response.getText()),
                conversationId,
                artifact == null ? null : artifact.getId(),
                artifact == null ? null : "/api/agent/artifacts/" + artifact.getId() + "/content",
                response.getTraceId(),
                response.getTotalIterations(),
                response.getTotalTokens(),
                response.getToolCallHistory(),
                generatedFileUrls(response),
                confirmationCards(response, userId)
        );
    }

    public List<CareSubject> subjects(HttpSession session) {
        requireEnabled();
        return subjectDirectory.subjects(currentUser(session));
    }

    public String newConversation(HttpSession session) {
        requireEnabled();
        String userId = currentUser(session);
        String conversationId = conversationService.create(userId, null).getConversationId();
        session.setAttribute("agentConversationId", conversationId);
        return conversationId;
    }

    private void requireEnabled() {
        if (!runtimeEnabled) {
            throw new IllegalStateException("Agent Runtime 未开启，已回退旧链路");
        }
    }

    private String currentUser(HttpSession session) {
        String userId = session == null ? null : (String) session.getAttribute("user");
        if (userId == null || userId.isBlank()) {
            throw new AgentAuthenticationException("请先登录后使用 Agent 工作台");
        }
        return userId;
    }

    private String resolveMessage(AgentChatRequest request) {
        if (request.message() != null && !request.message().isBlank()) {
            return request.message().trim();
        }
        if (request.artifactId() != null || request.imageBase64() != null) {
            return "请分析这张图片";
        }
        throw new IllegalArgumentException("请输入消息，或先上传图片");
    }

    private Artifact resolveArtifact(String userId, AgentChatRequest request) {
        if (request.artifactId() != null && !request.artifactId().isBlank()) {
            return artifactService.findOwned(request.artifactId(), userId)
                    .orElseThrow(() -> new IllegalArgumentException("图片不存在或不属于当前用户"));
        }
        if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
            return artifactService.storeBase64(userId, request.imageBase64());
        }
        return null;
    }

    private String resolveConversationId(AgentChatRequest request, HttpSession session,
                                         String userId, Long subjectId) {
        String requested = request.conversationId();
        if ((requested == null || requested.isBlank())
                && session.getAttribute("agentConversationId") instanceof String existing) {
            requested = existing;
        }
        String conversationId = conversationService.resolve(requested, userId, subjectId)
                .getConversationId();
        session.setAttribute("agentConversationId", conversationId);
        return conversationId;
    }

    private String systemPrompt(String userId, SubjectDirectoryService.ResolvedSubject subject,
                                Artifact artifact, List<String> retrieval) {
        StringBuilder prompt = new StringBuilder("""
                你是 WECHATILINK 的宠物/植物护理 Agent。请遵守：
                1. 使用中文，优先基于档案、事件、会话历史和工具返回的真实数据。
                2. 需要实时信息、专业知识、位置服务或图片理解时调用工具，不要编造。
                3. 附近服务结果中的导航链接必须原样保留。
                4. 涉及用药、急救或严重病害时谨慎建议，并提示必要时就医。
                5. 写入类操作只会生成确认卡片，不得承诺已经执行。
                """);

        if (subject != null) {
            CareSubject careSubject = subject.subject();
            prompt.append("\n当前护理对象：").append(careSubject.getSubjectType())
                    .append(" - ").append(careSubject.getName())
                    .append("，工具参数 targetId 使用 ").append(subject.effectiveSubjectId())
                    .append("。");
            appendField(prompt, "品种", careSubject.getSpecies());
            appendField(prompt, "细分品种", careSubject.getBreed());
            appendField(prompt, "档案", careSubject.getProfile());
            prompt.append('\n');
        }

        careEventRepository.findByUserIdAndSubjectIdOrderByOccurredAtDesc(userId,
                        subject == null ? null : subject.effectiveSubjectId())
                .stream()
                .limit(8)
                .forEach(event -> prompt.append("- ").append(event.getEventType())
                        .append("：").append(event.getPayload()).append('\n'));

        if (artifact != null) {
            if (artifact.getAnalysis() != null && !artifact.getAnalysis().isBlank()) {
                prompt.append("\n当前图片已有分析结果：").append(artifact.getAnalysis()).append('\n');
            } else {
                prompt.append("\n当前消息引用了一张图片。需要理解图片时调用 analyzeImage；疑似病害时调用 diagnoseDisease。\n");
            }
        }
        if (!retrieval.isEmpty()) {
            prompt.append("\n可参考且已按用户过滤的记忆/知识：\n");
            retrieval.forEach(item -> prompt.append("- ").append(item).append('\n'));
        }
        return prompt.toString();
    }

    private void appendField(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isBlank()) {
            prompt.append("，").append(label).append("：").append(value);
        }
    }

    private JSONArray promptMessages(List<AgentMessage> history, String systemPrompt, String message) {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.add(system);

        history.forEach(item -> {
            JSONObject value = new JSONObject();
            value.put("role", item.getRole());
            value.put("content", item.getContent());
            messages.add(value);
        });

        JSONObject current = new JSONObject();
        current.put("role", "user");
        current.put("content", message);
        messages.add(current);
        return messages;
    }

    private Set<String> allowedTools(AgentChatRequest request) {
        return request.allowedTools() == null ? Set.of() : request.allowedTools();
    }

    private List<AgentCard> confirmationCards(ToolCallResponse response, String userId) {
        List<AgentCard> cards = new ArrayList<>();
        if (response.getToolCallHistory() == null) {
            return cards;
        }
        for (var call : response.getToolCallHistory()) {
            Long confirmationId = confirmationService.confirmationId(call.getResult());
            if (confirmationId == null) {
                continue;
            }
            var confirmation = confirmationService.owned(confirmationId, userId);
            var card = confirmationService.card(confirmation);
            cards.add(new AgentCard(card.type(), card.title(), card.payload()));
        }
        return cards;
    }

    private String cleanReply(String reply) {
        return reply == null ? "" : reply.replaceAll("\\[CONFIRMATION:\\d+]", "").trim();
    }

    private List<String> generatedFileUrls(ToolCallResponse response) {
        if (response.getGeneratedFiles() == null) {
            return List.of();
        }
        return response.getGeneratedFiles().stream()
                .map(path -> "/uploads/" + path.getFileName())
                .toList();
    }
}
