package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class ToolCallingService {

    private static final int MAX_ITERATIONS = 5;

    private final LlmService llmService;
    private final ToolBroker toolBroker;
    private final ExecutorService toolExecutor;

    @Autowired
    public ToolCallingService(LlmService llmService, ToolBroker toolBroker) {
        this.llmService = llmService;
        this.toolBroker = toolBroker;
        this.toolExecutor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 4),
                r -> {
                    Thread t = new Thread(r, "tool-executor");
                    t.setDaemon(true);
                    return t;
                });
        log.info("ToolCallingService initialized with ToolBroker");
    }

    public Set<String> getRegisteredToolNames() {
        return toolBroker.registeredToolNames();
    }

    public JSONArray buildToolsSchema(Set<String> allowedToolNames, AgentContext context) {
        return toolBroker.buildSchema(allowedToolNames,
                context == null ? AgentContext.anonymous() : context);
    }

    public ToolCallResponse chatWithMessages(JSONArray messages,
                                             Set<String> allowedToolNames,
                                             AgentContext context) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        return executeToolLoop(messages, allowedToolNames, traceId,
                context == null ? AgentContext.anonymous() : context);
    }

    private ToolCallResponse executeToolLoop(JSONArray messages, Set<String> allowedToolNames,
                                              String traceId, AgentContext context) {
        JSONArray tools = toolBroker.buildSchema(allowedToolNames, context);
        List<ToolCallResult> toolCallHistory = new ArrayList<>();
        List<Path> generatedFiles = new ArrayList<>();
        StringBuilder accumulatedText = new StringBuilder();
        long totalTokens = 0;
        int iterations = 0;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            iterations = iteration + 1;
            log.info("[Trace:{}] Iteration {}/{}, messages: {}",
                    traceId, iterations, MAX_ITERATIONS, messages.size());

            try {
                JSONObject response = llmService.chatWithTools(messages, tools);

                long iterationTokens = extractTokens(response);
                totalTokens += iterationTokens;
                log.info("[Trace:{}] Tokens used this iteration: {}, total: {}",
                        traceId, iterationTokens, totalTokens);

                if (hasToolCalls(response)) {
                    JSONObject assistantMessage = getAssistantMessage(response);
                    if (assistantMessage != null) {
                        messages.add(assistantMessage);
                    }

                    List<ToolCallInfo> toolCalls = parseToolCalls(response);
                    log.info("[Trace:{}] Found {} tool calls, executing concurrently",
                            traceId, toolCalls.size());

                    Map<String, Future<ToolCallResult>> futureMap = new LinkedHashMap<>();

                    for (ToolCallInfo tc : toolCalls) {
                        futureMap.put(tc.id, CompletableFuture.supplyAsync(() -> {
                            long start = System.currentTimeMillis();
                            String result;
                            boolean success = true;
                            String errorMsg = null;

                            try {
                                log.info("[Trace:{}] Executing tool: {} with args: {}",
                                        traceId, tc.toolName, tc.arguments);
                                result = toolBroker.execute(tc.toolName, tc.arguments, traceId, context);
                                log.info("[Trace:{}] Tool {} completed in {}ms",
                                        traceId, tc.toolName, System.currentTimeMillis() - start);
                            } catch (Exception e) {
                                success = false;
                                errorMsg = e.getMessage();
                                result = "工具执行异常：" + e.getMessage();
                                log.error("[Trace:{}] Tool {} failed: {}",
                                        traceId, tc.toolName, e.getMessage());
                            }

                            long duration = System.currentTimeMillis() - start;
                            ToolCallResult callResult = success
                                    ? ToolCallResult.success(traceId, tc.toolName, tc.arguments, result, duration)
                                    : ToolCallResult.error(traceId, tc.toolName, tc.arguments, errorMsg, duration);

                            synchronized (generatedFiles) {
                                generatedFiles.addAll(extractGeneratedFiles(result));
                            }

                            return callResult;
                        }, toolExecutor));
                    }

                    for (Map.Entry<String, Future<ToolCallResult>> entry : futureMap.entrySet()) {
                        String toolCallId = entry.getKey();
                        ToolCallResult callResult = entry.getValue().get(10, TimeUnit.SECONDS);
                        toolCallHistory.add(callResult);

                        JSONObject toolMsg = new JSONObject();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("content", callResult.getResult());
                        messages.add(toolMsg);
                    }

                } else {
                    String content = getTextContent(response);
                    if (content != null && !content.isBlank()) {
                        if (accumulatedText.length() == 0) {
                            accumulatedText.append(content);
                        } else if (!accumulatedText.toString().equals(content)) {
                            accumulatedText.append("\n").append(content);
                        }
                    }

                    String finalText = accumulatedText.length() > 0
                            ? accumulatedText.toString()
                            : "抱歉，我无法处理您的请求。";

                    log.info("[Trace:{}] Final response after {} iterations, {} tool calls, {} total tokens",
                            traceId, iterations, toolCallHistory.size(), totalTokens);

                    return ToolCallResponse.builder()
                            .text(finalText)
                            .generatedFiles(generatedFiles)
                            .toolCallHistory(toolCallHistory)
                            .totalIterations(iterations)
                            .totalTokens(totalTokens)
                            .traceId(traceId)
                            .build();
                }

            } catch (Exception e) {
                log.error("[Trace:{}] Tool calling iteration {} failed: {}",
                        traceId, iteration, e.getMessage());

                if (iteration == MAX_ITERATIONS - 1) {
                    return ToolCallResponse.builder()
                            .text("处理请求时发生错误: " + e.getMessage())
                            .generatedFiles(generatedFiles)
                            .toolCallHistory(toolCallHistory)
                            .totalIterations(iterations)
                            .totalTokens(totalTokens)
                            .traceId(traceId)
                            .build();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        log.warn("[Trace:{}] Reached max iterations ({}), making final LLM fallback request",
                traceId, MAX_ITERATIONS);

        String fallbackText = accumulatedText.length() > 0
                ? accumulatedText.toString()
                : "处理请求超时，请稍后重试";

        try {
            JSONObject lastResponse = llmService.chatWithTools(messages, new JSONArray());
            long fallbackTokens = extractTokens(lastResponse);
            totalTokens += fallbackTokens;

            String lastContent = getTextContent(lastResponse);
            if (lastContent != null && !lastContent.isBlank()) {
                if (!fallbackText.equals(lastContent)) {
                    fallbackText = fallbackText + "\n" + lastContent;
                }
            }
            log.info("[Trace:{}] Final fallback completed, extra tokens: {}",
                    traceId, fallbackTokens);
        } catch (Exception e) {
            log.error("[Trace:{}] Final fallback request failed, using accumulated text",
                    traceId, e.getMessage());
        }

        return ToolCallResponse.builder()
                .text(fallbackText)
                .generatedFiles(generatedFiles)
                .toolCallHistory(toolCallHistory)
                .totalIterations(iterations + 1)
                .totalTokens(totalTokens)
                .traceId(traceId)
                .build();
    }

    private long extractTokens(JSONObject response) {
        try {
            JSONObject usage = response.getJSONObject("usage");
            if (usage != null) {
                long promptTokens = usage.getLongValue("prompt_tokens");
                long completionTokens = usage.getLongValue("completion_tokens");
                return promptTokens + completionTokens;
            }
        } catch (Exception e) {
            log.debug("Failed to extract tokens from response");
        }
        return 0;
    }

    private boolean hasToolCalls(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return false;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return false;

            JSONArray toolCalls = message.getJSONArray("tool_calls");
            return toolCalls != null && !toolCalls.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject getAssistantMessage(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;

            return choices.getJSONObject(0).getJSONObject("message");
        } catch (Exception e) {
            return null;
        }
    }

    private List<ToolCallInfo> parseToolCalls(JSONObject response) {
        List<ToolCallInfo> result = new ArrayList<>();

        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return result;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return result;

            JSONArray toolCalls = message.getJSONArray("tool_calls");
            if (toolCalls == null) return result;

            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String id = tc.getString("id");

                JSONObject function = tc.getJSONObject("function");
                String name = function.getString("name");
                String argsStr = function.getString("arguments");

                JSONObject args = new JSONObject();
                if (argsStr != null && !argsStr.isEmpty()) {
                    try {
                        args = JSON.parseObject(argsStr);
                    } catch (Exception e) {
                        log.warn("Failed to parse tool arguments: {}", argsStr);
                    }
                }

                result.add(new ToolCallInfo(id, name, args));
            }
        } catch (Exception e) {
            log.error("Failed to parse tool calls", e);
        }

        return result;
    }

    private String getTextContent(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return null;

            return message.getString("content");
        } catch (Exception e) {
            return null;
        }
    }

    public String executeTool(String toolName, JSONObject arguments, AgentContext context) {
        return toolBroker.execute(toolName, arguments, null, context);
    }

    private List<Path> extractGeneratedFiles(String result) {
        List<Path> files = new ArrayList<>();
        if (result == null) return files;

        try {
            if (result.startsWith("[IMAGE:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted image file: {}", path);
            }
            if (result.startsWith("[AUDIO:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted audio file: {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to extract generated files from result");
        }

        return files;
    }

    @PreDestroy
    public void shutdown() {
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                toolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class ToolCallInfo {
        final String id;
        final String toolName;
        final JSONObject arguments;

        ToolCallInfo(String id, String toolName, JSONObject arguments) {
            this.id = id;
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }
}
