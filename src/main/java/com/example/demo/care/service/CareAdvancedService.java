package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CareAdvancedService {

    private static final Logger log = LoggerFactory.getLogger(CareAdvancedService.class);

    private final WebSearchTool webSearchTool;
    private final WeatherService weatherService;

    public CareAdvancedService(WebSearchTool webSearchTool,
                               WeatherService weatherService) {
        this.webSearchTool = webSearchTool;
        this.weatherService = weatherService;
    }

    // ============ 症状分诊 ============

    public String triage(String symptoms, String duration, String age) {
        String value = symptoms == null ? "" : symptoms;

        if (value.matches(".*(呼吸困难|抽搐|无法排尿|昏迷|大量出血|误食.*毒|持续呕吐).*")) {
            return "紧急级别：立即就医。不要自行喂药或催吐；保持呼吸道通畅，携带误食物包装和既往记录前往宠物急诊。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("需要进一步评估。");
        if (symptoms != null && !symptoms.isBlank()) {
            sb.append("症状：").append(symptoms).append("；");
        }
        if (duration != null && !duration.isBlank()) {
            sb.append("持续时间：").append(duration).append("；");
        }
        if (age != null && !age.isBlank()) {
            sb.append("年龄：").append(age).append("；");
        }
        sb.append("请结合精神、饮食、排泄、体温等信息，必要时24小时内咨询兽医。");

        return sb.toString();
    }

    // ============ 用药与记录 ============

    public String weatherAlert(String city, String scene) {
        try {
            StringBuilder sb = new StringBuilder();

            WeatherResponse weather = weatherService.getWeatherByCity(city);
            sb.append("📍 ").append(weather.getCity()).append("天气：");

            if (weather.getCurrent() != null) {
                sb.append(weather.getCurrent().getWeather())
                  .append("，气温 ").append(weather.getCurrent().getTemperature()).append("°C");

                if (weather.getCurrent().getHumidity() != null) {
                    sb.append("，湿度 ").append(weather.getCurrent().getHumidity()).append("%");
                }
                if (weather.getCurrent().getWindSpeed() != null) {
                    sb.append("，风速 ").append(weather.getCurrent().getWindSpeed()).append("km/h");
                }

                sb.append("\n\n⚠️ 护理预警：\n");
                sb.append(generateWeatherAdvisory(weather, scene));
            }

            if (weather.getForecast() != null && !weather.getForecast().isEmpty()) {
                sb.append("\n📅 未来天气预报：\n");
                int days = Math.min(3, weather.getForecast().size());
                for (int i = 0; i < days; i++) {
                    WeatherResponse.ForecastDay day = weather.getForecast().get(i);
                    sb.append("- ").append(day.getDate() != null ? day.getDate().substring(5) : "")
                      .append(" ").append(day.getDayWeather() != null ? day.getDayWeather() : "")
                      .append(" ").append(day.getHighTemp() != null ? day.getHighTemp() + "°C" : "")
                      .append("/").append(day.getLowTemp() != null ? day.getLowTemp() + "°C" : "")
                      .append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Weather API failed, falling back to web search: {}", e.getMessage());
            return weatherAlertFallback(city, scene);
        }
    }

    private String generateWeatherAdvisory(WeatherResponse weather, String scene) {
        StringBuilder sb = new StringBuilder();
        WeatherResponse.CurrentWeather cur = weather.getCurrent();

        if (cur == null) return "天气数据不足，无法生成预警。";

        double temp = cur.getTemperature() != null ? cur.getTemperature() : 20;
        double humidity = cur.getHumidity() != null ? cur.getHumidity() : 50;
        String weatherDesc = cur.getWeather() != null ? cur.getWeather() : "";

        boolean isPet = scene != null && scene.contains("宠物");
        boolean isPlant = scene != null && scene.contains("植物");

        List<String> warnings = new ArrayList<>();

        // 高温预警
        if (temp >= 35) {
            warnings.add("🔥 高温预警：避免正午户外活动（10:00-16:00），增加饮水，注意防晒");
            if (isPet) warnings.add("宠物避暑：提供充足饮水，剪短毛发，避免水泥地烫伤脚掌");
            if (isPlant) warnings.add("植物防暑：增加浇水频率至早晚各一次，遮阴处理");
        } else if (temp >= 30) {
            warnings.add("☀️ 天气炎热：注意补水，观察精神状态");
            if (isPlant) warnings.add("植物：早晚浇水，避免中午暴晒");
        }

        // 低温预警
        if (temp <= 5) {
            warnings.add("❄️ 寒潮预警：注意保暖，减少外出时间");
            if (isPet) warnings.add("宠物保暖：提供毛毯，避免户外久留，注意防冻伤");
            if (isPlant) warnings.add("植物防寒：移至室内或覆盖保温材料，减少浇水");
        } else if (temp <= 10) {
            warnings.add("🌡️ 天气较冷：注意保暖，适当增加营养");
        }

        // 雨天预警
        if (weatherDesc.contains("雨")) {
            warnings.add("🌧️ 雨天提醒：关好门窗，保持干燥");
            if (isPet) warnings.add("宠物：减少户外散步，注意防滑，及时吹干毛发");
            if (isPlant) warnings.add("植物：减少浇水，注意排水，避免积水烂根");
        }

        // 湿度预警
        if (humidity >= 85) {
            warnings.add("💧 高湿度预警：注意防潮，防止霉菌滋生");
            if (isPlant) warnings.add("植物：减少浇水，加强通风，防止病害");
        } else if (humidity <= 30) {
            warnings.add("🏜️ 空气干燥：注意补水保湿");
            if (isPlant) warnings.add("植物：增加浇水频率，可使用加湿器");
        }

        // 大风预警
        if (cur.getWindSpeed() != null && cur.getWindSpeed() >= 40) {
            warnings.add("🌬️ 大风预警：户外活动注意安全，关好门窗");
        }

        // 如果没有预警，给出日常建议
        if (warnings.isEmpty()) {
            warnings.add("✅ 天气适宜，适合日常护理和户外活动");
            warnings.add("保持规律的饮食、运动和清洁习惯");
        }

        return String.join("\n", warnings);
    }

    private String weatherAlertFallback(String city, String scene) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", city + "天气");
            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            if (result.isSuccess()) {
                sb.append(result.getData()).append("\n");
            }
            sb.append("护理场景：").append(scene);

            return sb.toString();
        } catch (Exception e) {
            return "天气预警查询失败：" + e.getMessage();
        }
    }

    // ============ 智能护理计划（增强版） ============

    public String professionalSearch(String query) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", query);
            ToolResult<?> result = webSearchTool.execute(params);
            return result.isSuccess() ? String.valueOf(result.getData()) : "联网搜索失败：" + result.getMessage();
        } catch (Exception e) {
            return "专业查询失败：" + e.getMessage();
        }
    }
}
