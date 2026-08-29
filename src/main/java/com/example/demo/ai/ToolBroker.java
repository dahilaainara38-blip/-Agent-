package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.domain.ActionConfirmation;
import com.example.demo.agent.domain.ToolTrace;
import com.example.demo.agent.repository.ActionConfirmationRepository;
import com.example.demo.agent.repository.ToolTraceRepository;
import com.example.demo.weather.service.WeatherService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolBroker {

    private static final Set<String> WRITE_TOOLS = Set.of(
            "createCareReminder", "completeCareReminder", "saveMedication",
            "generateCarePlan", "generateImage", "editImage"
    );

    private static final Set<String> IDENTITY_TOOLS = Set.of(
            "analyzeImage", "editImage", "createCareReminder", "completeCareReminder",
            "listCareReminders", "saveMedication", "checkMedication",
            "compareImages", "generateCarePlan", "diagnoseDisease"
    );

    private static final Map<String, String> ACTION_TYPES = Map.of(
            "createCareReminder", "REMINDER_CREATE",
            "completeCareReminder", "REMINDER_COMPLETE",
            "saveMedication", "MEDICATION_SAVE",
            "generateCarePlan", "CARE_PLAN_GENERATE",
            "generateImage", "IMAGE_GENERATE",
            "editImage", "IMAGE_EDIT"
    );

    private final Map<String, ToolInfo> toolRegistry = new LinkedHashMap<>();
    private final ToolTraceRepository toolTraceRepository;
    private final ActionConfirmationRepository confirmationRepository;
    private final ExecutorService executor;
    private final long timeoutMs;

    public ToolBroker(SpringAiTools springAiTools,
                      WeatherService weatherService,
                      ToolTraceRepository toolTraceRepository,
                      ActionConfirmationRepository confirmationRepository,
                      @Value("${agent.tools.timeout-ms:8000}") long timeoutMs) {
        this.toolTraceRepository = toolTraceRepository;
        this.confirmationRepository = confirmationRepository;
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 4),
                task -> {
                    Thread thread = new Thread(task, "tool-broker");
                    thread.setDaemon(true);
                    return thread;
                });
        register(springAiTools);
        register(weatherService);
        log.info("ToolBroker initialized with {} tools", toolRegistry.size());
    }

    private void register(Object provider) {
        for (Method method : provider.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }
            String name = annotation.name().isBlank() ? method.getName() : annotation.name();
            toolRegistry.put(name, new ToolInfo(provider, method, annotation));
        }
    }

    public Set<String> registeredToolNames() {
        return new LinkedHashSet<>(toolRegistry.keySet());
    }

    public List<ToolMetadata> metadata() {
        return toolRegistry.entrySet().stream()
                .map(entry -> new ToolMetadata(
                        entry.getKey(),
                        description(entry.getValue()),
                        accessMode(entry.getKey()),
                        IDENTITY_TOOLS.contains(entry.getKey())))
                .toList();
    }

    public JSONArray buildSchema(Set<String> allowedTools, AgentContext context) {
        return toolRegistry.entrySet().stream()
                .filter(entry -> isSelected(entry.getKey(), allowedTools))
                .filter(entry -> isVisible(entry.getKey(), context))
                .map(entry -> buildFunctionSchema(entry.getKey(), entry.getValue()))
                .collect(JSONArray::new, JSONArray::add, JSONArray::addAll);
    }

    private boolean isSelected(String toolName, Set<String> allowedTools) {
        return allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(toolName);
    }

    private boolean isVisible(String toolName, AgentContext context) {
        return !IDENTITY_TOOLS.contains(toolName) || context.authenticated();
    }

    private JSONObject buildFunctionSchema(String name, ToolInfo info) {
        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description(info));
        function.put("parameters", buildParameters(info.method));

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private JSONObject buildParameters(Method method) {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(AgentContextParam.class)) {
                continue;
            }
            String name = parameter.getName();
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);

            JSONObject schema = new JSONObject();
            schema.put("type", jsonType(parameter.getType()));
            schema.put("description", annotation == null || annotation.description().isBlank()
                    ? "Parameter: " + name : annotation.description());
            properties.put(name, schema);
            if (annotation == null || annotation.required()) {
                required.add(name);
            }
        }

        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }
        return parameters;
    }

    private String jsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || List.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    public String execute(String toolName, JSONObject arguments, String traceId, AgentContext context) {
        ToolInfo info = toolRegistry.get(toolName);
        if (info == null) {
            throw new IllegalArgumentException("未知工具：" + toolName);
        }
        if (IDENTITY_TOOLS.contains(toolName) && !context.authenticated()) {
            throw new IllegalArgumentException("请先登录后再使用该工具");
        }

        validateArguments(toolName, info.method, arguments);
        long startedAt = System.currentTimeMillis();
        String safeArgs = sanitize(arguments).toJSONString();
        String accessMode = accessMode(toolName);
        Future<String> future = null;

        try {
            if (WRITE_TOOLS.contains(toolName) && !context.hasPermission(AgentContext.Permission.AGENT_WRITE)) {
                String result = requestConfirmation(toolName, arguments, context);
                audit(traceId, context, toolName, accessMode, "CONFIRMATION_REQUIRED",
                        safeArgs, result, null, System.currentTimeMillis() - startedAt);
                return result;
            }

            future = executor.submit(() -> invoke(info, arguments, context));
            String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            audit(traceId, context, toolName, accessMode, "SUCCESS",
                    safeArgs, truncate(result), null, System.currentTimeMillis() - startedAt);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            String message = "工具执行超时";
            audit(traceId, context, toolName, accessMode, "TIMEOUT",
                    safeArgs, null, message, System.currentTimeMillis() - startedAt);
            throw new IllegalStateException(message, e);
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException exception
                    ? exception.getTargetException() : unwrap(e);
            String message = cause.getMessage() == null ? "工具执行失败" : cause.getMessage();
            audit(traceId, context, toolName, accessMode, "ERROR",
                    safeArgs, null, message, System.currentTimeMillis() - startedAt);
            throw new RuntimeException(message, cause);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable.getCause();
        return cause == null ? throwable : cause;
    }

    private void validateArguments(String toolName, Method method, JSONObject arguments) {
        List<String> missing = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(AgentContextParam.class)) {
                continue;
            }
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            if (annotation != null && !annotation.required()) {
                continue;
            }
            Object value = arguments == null ? null : arguments.get(parameter.getName());
            if (value == null || value instanceof String text && text.isBlank()) {
                missing.add(parameter.getName());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("工具 " + toolName + " 缺少参数：" + String.join(", ", missing));
        }
    }

    private String invoke(ToolInfo info, JSONObject arguments, AgentContext context) throws Exception {
        Parameter[] parameters = info.method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.isAnnotationPresent(AgentContextParam.class)) {
                values[i] = context;
            } else {
                values[i] = arguments.containsKey(parameter.getName())
                        ? convert(arguments.get(parameter.getName()), parameter.getType())
                        : null;
            }
        }
        info.method.setAccessible(true);
        Object result = info.method.invoke(info.target, values);
        return result instanceof String text ? text : JSON.toJSONString(result);
    }

    private Object convert(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) return String.valueOf(value);
        if (targetType == int.class || targetType == Integer.class) return Integer.valueOf(String.valueOf(value));
        if (targetType == long.class || targetType == Long.class) return Long.valueOf(String.valueOf(value));
        if (targetType == double.class || targetType == Double.class) return Double.valueOf(String.valueOf(value));
        if (targetType == float.class || targetType == Float.class) return Float.valueOf(String.valueOf(value));
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.valueOf(String.valueOf(value));
        return value;
    }

    private String requestConfirmation(String toolName, JSONObject arguments, AgentContext context) {
        if (confirmationRepository == null) {
            throw new IllegalStateException("写操作确认服务不可用");
        }
        ActionConfirmation confirmation = confirmationRepository.save(ActionConfirmation.builder()
                .userId(context.userId())
                .conversationId(context.conversationId())
                .subjectId(context.subjectId())
                .toolName(toolName)
                .actionType(ACTION_TYPES.getOrDefault(toolName, "CARE_WRITE"))
                .payloadJson(sanitize(arguments).toJSONString())
                .status(ActionConfirmation.Status.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
        return "[CONFIRMATION:" + confirmation.getId() + "] 该操作需要确认后才会执行。";
    }

    private void audit(String traceId, AgentContext context, String toolName, String accessMode,
                       String status, String arguments, String result, String error, long duration) {
        if (toolTraceRepository == null) {
            return;
        }
        try {
            toolTraceRepository.save(ToolTrace.builder()
                    .traceId(traceId == null ? "direct" : traceId)
                    .conversationId(context.conversationId())
                    .userId(context.userId())
                    .toolName(toolName)
                    .accessMode(accessMode)
                    .status(status)
                    .argumentsJson(arguments)
                    .resultJson(result)
                    .errorMessage(error)
                    .durationMs(duration)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist tool trace for {}", toolName, e);
        }
    }

    private JSONObject sanitize(JSONObject source) {
        JSONObject target = new JSONObject();
        if (source == null) {
            return target;
        }
        source.forEach((key, value) -> target.put(key, sanitizeValue(String.valueOf(key), value)));
        return target;
    }

    private Object sanitizeValue(String key, Object value) {
        String lower = key.toLowerCase();
        if (lower.contains("password") || lower.contains("token") || lower.contains("secret")) {
            return "[REDACTED]";
        }
        if (lower.contains("base64") || lower.equals("image") || lower.equals("data")) {
            return "[OMITTED]";
        }
        if (value instanceof JSONObject object) {
            return sanitize(object);
        }
        return value;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 8000) {
            return value;
        }
        return value.substring(0, 8000) + "...[truncated]";
    }

    private String accessMode(String toolName) {
        return WRITE_TOOLS.contains(toolName) ? "WRITE" : "READ";
    }

    private String description(ToolInfo info) {
        return info.annotation.description().isBlank()
                ? info.method.getName() : info.annotation.description();
    }

    public List<String> validateToolNames(List<String> requestedTools) {
        if (requestedTools == null || requestedTools.isEmpty()) {
            return new ArrayList<>(toolRegistry.keySet());
        }
        return requestedTools.stream()
                .filter(toolRegistry::containsKey)
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record ToolMetadata(String name, String description, String accessMode, boolean requiresIdentity) {
    }

    private record ToolInfo(Object target, Method method, Tool annotation) {
    }
}
