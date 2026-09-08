package com.example.demo.chat;

import com.example.demo.chat.repository.sqlite.VectorStoreRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorStoreOrphanCleanupTest {

    @Test
    void initPurgesOrphansBeforeLoadingIndex() {
        VectorStoreRepository repository = mock(VectorStoreRepository.class);
        when(repository.deleteOrphanedVectors()).thenReturn(7);
        when(repository.findAll()).thenReturn(List.of());
        VectorStoreService service = service(repository);

        service.init();

        InOrder order = inOrder(repository);
        order.verify(repository).deleteOrphanedVectors();
        order.verify(repository).findAll();
    }

    @Test
    void purgeFailureDoesNotBlockIndexLoading() {
        VectorStoreRepository repository = mock(VectorStoreRepository.class);
        when(repository.deleteOrphanedVectors()).thenThrow(new RuntimeException("sqlite locked"));
        when(repository.findAll()).thenReturn(List.of());
        VectorStoreService service = service(repository);

        assertDoesNotThrow(service::init);

        org.mockito.Mockito.verify(repository).findAll();
    }

    private VectorStoreService service(VectorStoreRepository repository) {
        VectorStoreService service = new VectorStoreService(repository, mock(EmbeddingService.class));
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.5);
        return service;
    }
}
