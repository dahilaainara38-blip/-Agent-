package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.utils.JsonUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 底层 DashScope HTTP 客户端：只负责单轮 chat 与带工具的 chat。
 * 会话记忆、RAG、意图识别已统一收口到 Agent Runtime（AgentRuntimeService），
 * 本类不再做任何关键字意图路由。
 */
@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final DashScopeConfig config;
    private final CloseableHttpClient httpClient;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public LlmService(DashScopeConfig config,
                      @Value("${agent.llm.connect-timeout-ms:5000}") long connectTimeoutMs,
                      @Value("${agent.llm.read-timeout-ms:60000}") long readTimeoutMs,
                      @Value("${agent.llm.max-attempts:2}") int maxAttempts,
                      @Value("${agent.llm.retry-backoff-ms:500}") long retryBackoffMs) {
        this.config = config;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();
        this.httpClient = HttpClients.custom()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public String chat(String userMessage) throws IOException {
        return chat(userMessage, null);
    }

    public String chat(String userMessage, String systemPrompt) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());

        JSONArray messages = new JSONArray();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }

        JSONObject userMessageObj = new JSONObject();
        userMessageObj.put("role", "user");
        userMessageObj.put("content", userMessage);

        messages.add(userMessageObj);
        requestBody.put("messages", messages);

        return executeChatRequest(requestBody);
    }

    private String executeChatRequest(JSONObject requestBody) throws IOException {
        JSONObject response = executeChatRequestWithResponse(requestBody);
        return parseResponse(JSON.toJSONString(response));
    }

    public JSONObject executeChatRequestWithResponse(JSONObject requestBody) throws IOException {
        String jsonRequest = JSON.toJSONString(requestBody);

        for (int attempt = 1; ; attempt++) {
            RawResponse raw = doPost(jsonRequest);
            logger.info("LLM API response status: {}, attempt: {}/{}", raw.status(), attempt, maxAttempts);

            if (raw.status() == 200) {
                try {
                    return JSON.parseObject(raw.body());
                } catch (Exception e) {
                    throw new IOException("Failed to parse LLM response", e);
                }
            }

            if (!isRetryable(raw.status()) || attempt >= maxAttempts) {
                throw new IOException("LLM API request failed with status: " + raw.status()
                        + ", body: " + raw.body());
            }

            long backoff = retryBackoffMs * attempt;
            logger.warn("LLM API retryable status {}, backing off {}ms (attempt {}/{})",
                    raw.status(), backoff, attempt, maxAttempts);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("LLM request interrupted", e);
            }
        }
    }

    private RawResponse doPost(String jsonRequest) throws IOException {
        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/chat/completions");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            String responseBody = entity != null ? EntityUtils.toString(entity, "UTF-8") : "";
            return new RawResponse(response.getCode(), responseBody);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Failed to parse LLM response", e);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private record RawResponse(int status, String body) {
    }

    public JSONObject chatWithTools(JSONArray messages, JSONArray tools) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
        }

        logger.debug("Chat with tools request, tools count: {}", tools != null ? tools.size() : 0);

        return executeChatRequestWithResponse(requestBody);
    }

    /**
     * 流式工具对话：SSE 增量转发 content（onDelta），聚合后返回与非流式完全同形的响应
     * （choices[0].message 含 content/tool_calls，usage 尽力提取），上层工具循环无感复用。
     */
    public JSONObject chatWithToolsStream(JSONArray messages, JSONArray tools,
                                          java.util.function.Consumer<String> onDelta) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);
        requestBody.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
        }

        String jsonRequest = JSON.toJSONString(requestBody);
        for (int attempt = 1; ; attempt++) {
            StreamOutcome outcome = doStreamPost(jsonRequest, onDelta);
            if (outcome.response != null) {
                logger.info("LLM stream completed, attempt: {}/{}", attempt, maxAttempts);
                return outcome.response;
            }
            // 已开始向调用方输出增量后不可重试，避免重复推送；此处失败必然发生在首字节前
            if (!isRetryable(outcome.status) || attempt >= maxAttempts) {
                throw new IOException("LLM stream request failed with status: " + outcome.status
                        + ", body: " + outcome.errorBody);
            }
            long backoff = retryBackoffMs * attempt;
            logger.warn("LLM stream retryable status {}, backing off {}ms", outcome.status, backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("LLM stream request interrupted", e);
            }
        }
    }

    private StreamOutcome doStreamPost(String jsonRequest, java.util.function.Consumer<String> onDelta) throws IOException {
        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/chat/completions");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept", "text/event-stream");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            int status = response.getCode();
            HttpEntity entity = response.getEntity();
            if (status != 200) {
                String body;
                try {
                    body = entity != null ? EntityUtils.toString(entity, "UTF-8") : "";
                } catch (org.apache.hc.core5.http.ParseException e) {
                    body = "";
                }
                return new StreamOutcome(status, body, null);
            }
            StreamAggregator aggregator = new StreamAggregator(onDelta);
            if (entity != null) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(entity.getContent(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        aggregator.acceptLine(line);
                    }
                }
            }
            return new StreamOutcome(200, null, aggregator.buildResponse());
        }
    }

    /** SSE 分片聚合：content 增量实时转发，tool_calls 按 index 重组，产出与非流式同形的响应。 */
    static final class StreamAggregator {

        private final java.util.function.Consumer<String> onDelta;
        private final StringBuilder content = new StringBuilder();
        private final java.util.Map<Integer, JSONObject> toolCalls = new java.util.LinkedHashMap<>();
        private Long promptTokens;
        private Long completionTokens;
        private String finishReason;

        StreamAggregator(java.util.function.Consumer<String> onDelta) {
            this.onDelta = onDelta;
        }

        void acceptLine(String raw) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(":")) {
                return;
            }
            if (!line.startsWith("data:")) {
                return;
            }
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) {
                return;
            }
            JSONObject chunk;
            try {
                chunk = JSON.parseObject(payload);
            } catch (Exception e) {
                logger.debug("Skip unparsable SSE line: {}", payload);
                return;
            }
            if (chunk == null) {
                return;
            }

            JSONArray choices = chunk.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);
                if (choice.getString("finish_reason") != null) {
                    finishReason = choice.getString("finish_reason");
                }
                JSONObject delta = choice.getJSONObject("delta");
                if (delta != null) {
                    String text = delta.getString("content");
                    if (text != null && !text.isEmpty()) {
                        content.append(text);
                        if (onDelta != null) {
                            onDelta.accept(text);
                        }
                    }
                    JSONArray pieces = delta.getJSONArray("tool_calls");
                    if (pieces != null) {
                        mergeToolCalls(pieces);
                    }
                }
            }
            JSONObject usage = chunk.getJSONObject("usage");
            if (usage != null) {
                promptTokens = usage.getLong("prompt_tokens");
                completionTokens = usage.getLong("completion_tokens");
            }
        }

        private void mergeToolCalls(JSONArray pieces) {
            for (int i = 0; i < pieces.size(); i++) {
                JSONObject piece = pieces.getJSONObject(i);
                Integer index = piece.getInteger("index");
                if (index == null) {
                    index = 0;
                }
                JSONObject target = toolCalls.computeIfAbsent(index, idx -> {
                    JSONObject fresh = new JSONObject();
                    fresh.put("id", "call_" + idx);
                    fresh.put("type", "function");
                    fresh.put("function", new JSONObject());
                    return fresh;
                });
                if (piece.getString("id") != null) {
                    target.put("id", piece.getString("id"));
                }
                JSONObject function = piece.getJSONObject("function");
                if (function != null) {
                    JSONObject targetFunction = target.getJSONObject("function");
                    if (function.getString("name") != null) {
                        targetFunction.put("name", function.getString("name"));
                    }
                    String arguments = function.getString("arguments");
                    if (arguments != null) {
                        String existing = targetFunction.getString("arguments");
                        targetFunction.put("arguments", existing == null ? arguments : existing + arguments);
                    }
                }
            }
        }

        JSONObject buildResponse() {
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            if (content.length() > 0) {
                message.put("content", content.toString());
            }
            if (!toolCalls.isEmpty()) {
                message.put("tool_calls", new JSONArray(toolCalls.values()));
            }
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            }
            JSONObject body = new JSONObject();
            body.put("choices", new JSONArray(java.util.List.of(choice)));
            if (promptTokens != null || completionTokens != null) {
                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", promptTokens == null ? 0L : promptTokens);
                usage.put("completion_tokens", completionTokens == null ? 0L : completionTokens);
                body.put("usage", usage);
            }
            return body;
        }
    }

    private static final class StreamOutcome {
        final int status;
        final String errorBody;
        final JSONObject response;

        StreamOutcome(int status, String errorBody, JSONObject response) {
            this.status = status;
            this.errorBody = errorBody;
            this.response = response;
        }
    }

    private String parseResponse(String responseBody) {
        logger.debug("Parsing LLM response: {}", responseBody);

        JSONObject responseJson = JSON.parseObject(responseBody);

        if (responseJson == null || !responseJson.containsKey("choices")) {
            logger.warn("LLM response does not contain choices: {}", responseBody);
            return "抱歉，我现在无法回答你的问题。";
        }

        Object choices = responseJson.get("choices");
        JSONArray choicesArray;

        if (choices instanceof JSONArray) {
            choicesArray = (JSONArray) choices;
        } else if (choices instanceof Object[]) {
            choicesArray = new JSONArray();
            for (Object item : (Object[]) choices) {
                choicesArray.add(item);
            }
        } else {
            logger.warn("LLM choices is not an array: {}", choices);
            return "抱歉，我现在无法回答你的问题。";
        }

        if (choicesArray.isEmpty()) {
            logger.warn("LLM choices array is empty");
            return "抱歉，我现在无法回答你的问题。";
        }

        Object choiceObj = choicesArray.get(0);
        if (!(choiceObj instanceof JSONObject)) {
            logger.warn("LLM choice is not a JSONObject: {}", choiceObj);
            return "抱歉，我现在无法回答你的问题。";
        }

        JSONObject choice = (JSONObject) choiceObj;
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof JSONObject)) {
            logger.warn("LLM message is not a JSONObject: {}", messageObj);
            return "抱歉，我现在无法回答你的问题。";
        }

        JSONObject message = (JSONObject) messageObj;
        String content = message.getString("content");

        if (content == null) {
            logger.warn("LLM content is null");
            return "抱歉，我现在无法回答你的问题。";
        }

        return formatText(content);
    }

    private String formatText(String text) {
        text = JsonUtils.unescapeJson(text);
        text = text.replaceAll("---+", "");
        text = formatPoetry(text);
        return text;
    }

    private String formatPoetry(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        int poemLineCount = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                sb.append("\n");
                poemLineCount = 0;
                continue;
            }

            boolean isTitle = line.matches("《.+》");
            boolean isAuthor = line.contains("作者：") || line.contains("作者:");

            if (isTitle || isAuthor) {
                sb.append(line).append("\n\n");
                poemLineCount = 0;
                continue;
            }

            boolean isPoemLine = line.matches(".*[，。！？、；：].*") && !line.contains("：") && !line.startsWith("\"");

            if (isPoemLine) {
                sb.append(line);
                poemLineCount++;
                if (poemLineCount >= 2) {
                    sb.append("\n\n");
                    poemLineCount = 0;
                } else {
                    sb.append("    ");
                }
            } else {
                sb.append(line).append("\n");
                poemLineCount = 0;
            }
        }

        return sb.toString().trim();
    }
}
