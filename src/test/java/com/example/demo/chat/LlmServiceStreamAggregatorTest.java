package com.example.demo.chat;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmServiceStreamAggregatorTest {

    @Test
    void textDeltasAreForwardedAndAggregatedIntoResponse() {
        List<String> deltas = new ArrayList<>();
        LlmService.StreamAggregator aggregator = new LlmService.StreamAggregator(deltas::add);

        aggregator.acceptLine(": ping");
        aggregator.acceptLine("data: {\"choices\":[{\"delta\":{\"content\":\"今天\"}}]}");
        aggregator.acceptLine("data: {\"choices\":[{\"delta\":{\"content\":\"北京\"},\"finish_reason\":null}]}");
        aggregator.acceptLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        aggregator.acceptLine("data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}");
        aggregator.acceptLine("data: [DONE]");

        JSONObject response = aggregator.buildResponse();
        assertEquals(List.of("今天", "北京"), deltas);
        assertEquals("今天北京", response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"));
        assertEquals("stop", response.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
        assertEquals(11, response.getJSONObject("usage").getIntValue("prompt_tokens"));
    }

    @Test
    void toolCallFragmentsAreMergedByIndex() {
        LlmService.StreamAggregator aggregator = new LlmService.StreamAggregator(null);

        aggregator.acceptLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"ci\"}}]}}]}");
        aggregator.acceptLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"ty\\\":\\\"北京\\\"}\"}}]}}]}");
        aggregator.acceptLine("data: [DONE]");

        JSONObject message = aggregator.buildResponse()
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        assertEquals(null, message.getString("content"));
        JSONArray toolCalls = message.getJSONArray("tool_calls");
        assertEquals(1, toolCalls.size());
        JSONObject function = toolCalls.getJSONObject(0).getJSONObject("function");
        assertEquals("getWeather", function.getString("name"));
        assertEquals("{\"city\":\"北京\"}", function.getString("arguments"));
    }
}
