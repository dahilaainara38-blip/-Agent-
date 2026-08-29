package com.example.demo.chat;

import com.example.demo.chat.entity.sqlite.VectorStore;
import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorStoreServiceFilterTest {

    private VectorStoreService service;

    @BeforeEach
    void setUp() throws Exception {
        VectorStoreRepository repository = mock(VectorStoreRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        service = new VectorStoreService(repository, embeddingService);
        ReflectionTestUtils.setField(service, "topK", 10);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.0);
        ReflectionTestUtils.setField(service, "indexReady", new AtomicBoolean(true));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        service.addToIndex(1, vector("user-a memory", "agent_a", null, "user-a"), new float[]{1.0f, 0.0f});
        service.addToIndex(2, vector("user-b memory", "agent_b", null, "user-b"), new float[]{1.0f, 0.0f});
        service.addToIndex(3, vector("public knowledge", null, "care-doc", null), new float[]{1.0f, 0.0f});
        service.addToIndex(4, vector("legacy private", "web_a", null, null), new float[]{1.0f, 0.0f});
    }

    @Test
    void userRetrievalNeverReturnsAnotherUsersVector() {
        List<String> results = service.searchForUser("query", "user-a", "agent_a");

        assertEquals(List.of("user-a memory", "public knowledge"), results);
    }

    @Test
    void anonymousRetrievalOnlyUsesPublicKnowledge() {
        List<String> results = service.searchSimilar("query");

        assertEquals(List.of("public knowledge"), results);
    }

    @Test
    void legacyConversationIdStillScopesOldVectors() {
        List<String> results = service.searchSimilar("query", "web_a");

        assertEquals(List.of("public knowledge", "legacy private"), results);
    }

    private VectorStore vector(String content, String conversationId, String sourceId, String ownerId) {
        VectorStore vectorStore = new VectorStore("doc-" + content, content, new byte[0]);
        vectorStore.setConversationId(conversationId);
        vectorStore.setSourceId(sourceId);
        vectorStore.setOwnerId(ownerId);
        return vectorStore;
    }
}
