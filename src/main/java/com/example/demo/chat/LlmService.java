package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.utils.JsonUtils;
import com.example.demo.weather.tool.WeatherTool;

import com.example.demo.care.service.NearbyServiceSearchService;
import com.example.demo.care.service.PetFoodSafetyService;
import com.example.demo.care.service.PlantSafetyQueryService;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final DashScopeConfig config;
    private final ChatMemoryService chatMemoryService;
    private final CloseableHttpClient httpClient;
    
    @Autowired
    @Lazy
    private VectorStoreService vectorStoreService;

    @Autowired
    @Lazy
    private WeatherTool weatherTool;

    @Autowired
    @Lazy
    private NearbyServiceSearchService nearbyServiceSearchService;

    @Autowired
    @Lazy
    private PetFoodSafetyService petFoodSafetyService;

    @Autowired
    @Lazy
    private PlantSafetyQueryService plantSafetyQueryService;

    public LlmService(DashScopeConfig config, ChatMemoryService chatMemoryService) {
        this.config = config;
        this.chatMemoryService = chatMemoryService;
        this.httpClient = HttpClients.createDefault();
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
        
        HttpPost httpPost = new HttpPost(config.getBaseUrl() + "/chat/completions");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());
        httpPost.setEntity(new StringEntity(jsonRequest, ContentType.APPLICATION_JSON));
        
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            String responseBody = "";
            if (entity != null) {
                responseBody = EntityUtils.toString(entity, "UTF-8");
            }
            
            logger.info("LLM API response status: {}, body: {}", response.getCode(), responseBody);
            
            if (response.getCode() != 200) {
                throw new IOException("LLM API request failed with status: " + response.getCode() + ", body: " + responseBody);
            }
            
            return JSON.parseObject(responseBody);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Failed to parse LLM response", e);
        }
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

    public String chatWithMemory(String conversationId, String userMessage) throws IOException {
        return chatWithMemory(conversationId, userMessage, null);
    }

    public String chatWithMemory(String conversationId, String userMessage, String systemPrompt) throws IOException {
        logger.info("Chat with memory, conversationId: {}, userMessage: {}", conversationId, userMessage);
        
        List<ChatMessage> promptMessages = chatMemoryService.buildPromptMessages(conversationId, systemPrompt, userMessage);
        
        String ragContext = retrieveRagContext(userMessage, conversationId);
        if (ragContext != null && !ragContext.isEmpty()) {
            String ragSystemMessage = "参考以下历史对话信息，帮助回答用户当前问题：\n\n" + ragContext;
            if (systemPrompt == null) {
                systemPrompt = ragSystemMessage;
            } else {
                systemPrompt = systemPrompt + "\n\n" + ragSystemMessage;
            }
            logger.info("RAG context retrieved, length: {} chars", ragContext.length());
        }
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ChatMessage systemMsg = new ChatMessage("system", systemPrompt);
            if (promptMessages.isEmpty() || !"system".equals(promptMessages.get(0).getRole())) {
                promptMessages.add(0, systemMsg);
            } else {
                promptMessages.set(0, systemMsg);
            }
        }
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getModel());
        
        JSONArray messages = new JSONArray();
        for (ChatMessage msg : promptMessages) {
            JSONObject messageObj = new JSONObject();
            messageObj.put("role", msg.getRole());
            messageObj.put("content", msg.getContent());
            messages.add(messageObj);
        }
        requestBody.put("messages", messages);
        
        if (weatherTool != null && WeatherTool.matchesIntent(userMessage)) {
            String city = WeatherTool.extractCity(userMessage);
            if (city != null) {
                JSONObject weatherParams = new JSONObject();
                weatherParams.put("city", city);
                String weatherData = weatherTool.execute(weatherParams).getData();
                String toolInfo = "你可以使用以下工具获取实时数据：\n" +
                    "工具名称: getWeather\n" +
                    "功能描述: 查询指定城市的天气信息\n" +
                    "已获取到天气数据：\n" + weatherData;
                
                JSONObject toolMessage = new JSONObject();
                toolMessage.put("role", "system");
                toolMessage.put("content", toolInfo);
                messages.add(toolMessage);
                
                logger.info("Weather tool injected into prompt, city: {}", city);
            }
        }
        
        // ===== Nearby service search injection =====
        if (nearbyServiceSearchService != null && matchesNearbyIntent(userMessage)) {
            try {
                String serviceType = extractNearbyServiceType(userMessage);
                String location = extractLocation(userMessage);
                if (location != null && !location.isBlank()) {
                    String nearbyResult = nearbyServiceSearchService.searchNearbyService(serviceType, location);
                    JSONObject nearbyMsg = new JSONObject();
                    nearbyMsg.put("role", "system");
                    nearbyMsg.put("content", "以下是附近服务搜索结果，请基于这些真实数据回答用户：\n" + nearbyResult);
                    messages.add(nearbyMsg);
                    logger.info("Nearby service injected, type: {}, location: {}", serviceType, location);
                }
            } catch (Exception e) {
                logger.warn("Nearby service injection failed: {}", e.getMessage());
            }
        }

        // ===== Pet food safety injection =====
        if (petFoodSafetyService != null && matchesFoodSafetyIntent(userMessage)) {
            try {
                String foodName = extractFoodName(userMessage);
                String petType = extractPetType(userMessage);
                if (foodName != null && !foodName.isBlank()) {
                    String foodResult = petFoodSafetyService.queryFoodSafety(foodName, petType);
                    JSONObject foodMsg = new JSONObject();
                    foodMsg.put("role", "system");
                    foodMsg.put("content", foodResult);
                    messages.add(foodMsg);
                    logger.info("Food safety injected, food: {}, pet: {}", foodName, petType);
                }
            } catch (Exception e) {
                logger.warn("Food safety injection failed: {}", e.getMessage());
            }
        }

        // ===== Plant safety injection =====
        if (plantSafetyQueryService != null && matchesPlantSafetyIntent(userMessage)) {
            try {
                String plantName = extractPlantName(userMessage);
                if (plantName != null && !plantName.isBlank()) {
                    String plantResult = plantSafetyQueryService.queryPlantSafety("toxicity", plantName,
                            "该植物对宠物是否有毒？误食会有什么症状？应急处理方法？");
                    JSONObject plantMsg = new JSONObject();
                    plantMsg.put("role", "system");
                    plantMsg.put("content", plantResult);
                    messages.add(plantMsg);
                    logger.info("Plant safety injected, plant: {}", plantName);
                }
            } catch (Exception e) {
                logger.warn("Plant safety injection failed: {}", e.getMessage());
            }
        }

        logger.debug("Full request with history: {}", JSON.toJSONString(requestBody));
        
        String reply = executeChatRequest(requestBody);
        chatMemoryService.saveMessagePair(conversationId, userMessage, reply);
        
        asyncSaveVector(conversationId, userMessage, reply);
        
        return reply;
    }
    
    private String retrieveRagContext(String query, String conversationId) {
        try {
            if (vectorStoreService == null) {
                return null;
            }
            List<String> similarMessages = vectorStoreService.searchSimilar(query, conversationId);
            if (similarMessages.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < similarMessages.size(); i++) {
                sb.append("相关对话 ").append(i + 1).append(":\n");
                sb.append(similarMessages.get(i)).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            logger.warn("Failed to retrieve RAG context: {}", e.getMessage());
            return null;
        }
    }
    
    @Async
    public void asyncSaveVector(String conversationId, String userMessage, String assistantReply) {
        try {
            if (vectorStoreService != null) {
                vectorStoreService.saveMessage(conversationId, userMessage, assistantReply);
            }
        } catch (Exception e) {
            logger.error("Failed to async save vector", e);
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

    // ============ Intent detection: nearby service search ============

    private static boolean matchesNearbyIntent(String userMessage) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        String[] serviceKeywords = {"宠物医院", "动物医院", "急诊", "诊所", "植物医院", "园艺", "宠物店", "美容"};
        boolean hasService = false;
        for (String kw : serviceKeywords) {
            if (msg.contains(kw)) { hasService = true; break; }
        }
        if (!hasService) return false;
        String[] nearbyKeywords = {"附近", "最近", "周围", "就近", "哪里有", "哪有", "搜索", "找"};
        for (String kw : nearbyKeywords) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }

    private static String extractNearbyServiceType(String userMessage) {
        if (userMessage == null) return "hospital";
        String msg = userMessage.toLowerCase();
        if (msg.contains("急诊")) return "emergency";
        if (msg.contains("诊所")) return "clinic";
        if (msg.contains("植物医院")) return "plant_hospital";
        if (msg.contains("园艺")) return "gardening";
        if (msg.contains("宠物店")) return "pet_shop";
        if (msg.contains("美容")) return "grooming";
        return "hospital";
    }

    private static String extractLocation(String userMessage) {
        if (userMessage == null) return null;
        // Pattern: 我在XXX，/搜索/找/附近/帮
        int idx = userMessage.indexOf("我在");
        if (idx >= 0) {
            String after = userMessage.substring(idx + 2);
            int end = after.length();
            String[] delimiters = {"，", ",", "。", ".", " ", "搜索", "找", "帮", "请问", "想", "附近", "周边", "周围"};
            for (String d : delimiters) {
                int pos = after.indexOf(d);
                if (pos >= 0 && pos < end) end = pos;
            }
            String loc = after.substring(0, end).trim();
            if (loc.length() >= 2) return loc;
        }
        // Pattern: XXX附近 (without "我在")
        int nearbyIdx = userMessage.indexOf("附近");
        if (nearbyIdx > 0) {
            String before = userMessage.substring(0, nearbyIdx).trim();
            if (before.endsWith("的")) before = before.substring(0, before.length() - 1).trim();
            if (before.endsWith("在")) before = before.substring(0, before.length() - 1).trim();
            if (before.endsWith("我")) before = before.substring(0, before.length() - 1).trim();
            if (before.length() >= 2) return before;
        }
        return null;
    }

    // ============ Intent detection: pet food safety ============

    private static final String[] FOOD_KEYWORDS = {
        "巧克力", "葡萄干", "葡萄", "洋葱", "大蒜", "韭菜", "牛奶", "咖啡", "茶",
        "木糖醇", "啤酒", "骨头", "夏威夷果", "澳洲坚果", "樱桃", "柠檬",
        "柑橘", "柿子", "牛油果", "鳄梨", "生鸡蛋", "生肉", "食盐", "饼干", "糖果", "蛋糕", "酒"
    };

    private static boolean matchesFoodSafetyIntent(String userMessage) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        boolean hasPet = msg.contains("猫") || msg.contains("狗") || msg.contains("宠物");
        if (!hasPet) return false;
        // Exclude plant safety questions
        for (String kw : PLANT_KEYWORDS) {
            if (msg.contains(kw)) return false;
        }
        boolean hasFoodAction = msg.contains("能吃") || msg.contains("可以吃") || msg.contains("不能吃")
            || msg.contains("吃了") || msg.contains("偷吃") || msg.contains("误食")
            || msg.contains("有毒") || msg.contains("喂");
        if (hasFoodAction) return true;
        for (String kw : FOOD_KEYWORDS) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }

    private static String extractFoodName(String userMessage) {
        if (userMessage == null) return null;
        String msg = userMessage.toLowerCase();
        // Check food keywords first (longer ones are earlier in array)
        for (String kw : FOOD_KEYWORDS) {
            if (msg.contains(kw)) return kw;
        }
        // Pattern: 吃了XXX / 偷吃XXX / 误食XXX / 能吃XXX
        String[] verbs = {"吃了", "偷吃", "误食", "能吃", "可以吃", "不能吃"};
        for (String verb : verbs) {
            int idx = msg.indexOf(verb);
            if (idx >= 0) {
                String after = msg.substring(idx + verb.length());
                int end = after.length();
                String[] delimiters = {"了", "吗", "怎么办", "有事", "没事", "对", "会", "，", ",", "。", "."};
                for (String d : delimiters) {
                    int pos = after.indexOf(d);
                    if (pos >= 0 && pos < end) end = pos;
                }
                String food = after.substring(0, end).trim();
                if (food.length() >= 2) return food;
            }
        }
        // Pattern: XXX对猫/狗有毒
        int idx = msg.indexOf("对");
        if (idx > 0) {
            String before = msg.substring(0, idx).trim();
            String[] prefixes = {"我家", "我的", "猫吃了", "狗吃了"};
            for (String p : prefixes) {
                if (before.startsWith(p)) before = before.substring(p.length()).trim();
            }
            if (before.length() >= 2) return before;
        }
        return null;
    }

    private static String extractPetType(String userMessage) {
        if (userMessage == null) return "猫";
        String msg = userMessage.toLowerCase();
        if (msg.contains("狗") || msg.contains("犬")) return "狗";
        return "猫";
    }

    // ============ Intent detection: plant safety ============

    private static final String[] PLANT_KEYWORDS = {
        "百合", "绿萝", "龟背竹", "滴水观音", "郁金香", "水仙", "夹竹桃", "杜鹃",
        "一品红", "仙人掌", "芦荟", "常春藤", "散尾葵", "万年青", "海芋", "风信子",
        "发财树", "富贵竹", "吊兰", "多肉", "玫瑰", "月季", "茉莉", "栀子花",
        "虎皮兰", "橡皮树", "琴叶榕", "龙血树", "白掌", "红掌", "君子兰", "文竹"
    };

    private static boolean matchesPlantSafetyIntent(String userMessage) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        boolean hasPet = msg.contains("猫") || msg.contains("狗") || msg.contains("宠物");
        if (!hasPet) return false;
        for (String kw : PLANT_KEYWORDS) {
            if (msg.contains(kw)) return true;
        }
        if ((msg.contains("植物") || msg.contains("花") || msg.contains("草"))
                && (msg.contains("毒") || msg.contains("安全") || msg.contains("误食"))) {
            return true;
        }
        return false;
    }

    private static String extractPlantName(String userMessage) {
        if (userMessage == null) return null;
        String msg = userMessage.toLowerCase();
        for (String kw : PLANT_KEYWORDS) {
            if (msg.contains(kw)) return kw;
        }
        // Pattern: XXX对猫/狗有毒
        int idx = msg.indexOf("对");
        if (idx > 0) {
            String before = msg.substring(0, idx).trim();
            String[] prefixes = {"我家", "我的", "家里养了", "养了"};
            for (String p : prefixes) {
                if (before.startsWith(p)) before = before.substring(p.length()).trim();
            }
            if (before.length() >= 2) return before;
        }
        return null;
    }
}
