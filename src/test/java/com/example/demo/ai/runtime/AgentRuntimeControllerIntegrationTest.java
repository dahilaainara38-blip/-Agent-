package com.example.demo.ai.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentRuntimeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void agentEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/agent/subjects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void careQaRoutesThroughAgentRuntimeOnly() throws Exception {
        String body = "{\"question\":\"你好\",\"targetType\":\"PET\",\"targetId\":12}";
        mockMvc.perform(post("/api/care/qa").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("问答失败：请先登录后使用 Agent 工作台"));
    }

    @Test
    void toolCatalogExposesAllBrokeredTools() throws Exception {
        mockMvc.perform(get("/api/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(23));
    }

    @Test
    void tracesRequireAuthenticationAndOwnedConversation() throws Exception {
        mockMvc.perform(get("/api/agent/traces/agent_1"))
                .andExpect(status().isUnauthorized());

        MockHttpSession session = authenticated();
        mockMvc.perform(get("/api/agent/traces/agent_other").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("会话不存在或不属于当前用户"));
    }

    @Test
    void authenticatedSessionCanCreateConversationAndStoreArtifact() throws Exception {
        MockHttpSession session = authenticated();

        mockMvc.perform(post("/api/agent/conversations/new").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isNotEmpty());

        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2};
        MvcResult upload = mockMvc.perform(multipart("/api/agent/artifacts")
                        .file(new MockMultipartFile("file", "cat.png", "image/png", png))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").isNotEmpty())
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andReturn();
        assertTrue(upload.getResponse().getContentAsString().contains("image/png"));

        String body = upload.getResponse().getContentAsString();
        String artifactId = body.replaceAll(".*\"artifactId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(get("/api/agent/artifacts/" + artifactId + "/content").session(session))
                .andExpect(status().isOk());
    }

    private MockHttpSession authenticated() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "integration-user");
        return session;
    }
}
