package com.example.demo.agent.service;

import com.example.demo.agent.domain.Artifact;
import com.example.demo.agent.repository.ArtifactRepository;
import com.example.demo.vision.VisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactServiceTest {

    @TempDir
    Path tempDir;

    private ArtifactRepository repository;
    private VisionService visionService;
    private ArtifactService service;

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactRepository.class);
        visionService = mock(VisionService.class);
        service = new ArtifactService(repository, visionService, tempDir.toString());
    }

    @Test
    void base64ImageIsStoredOutsideMessageAndAnalyzedByReference() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2};
        String base64 = Base64.getEncoder().encodeToString(bytes);
        List<Artifact> saved = new ArrayList<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            Artifact value = invocation.getArgument(0);
            saved.add(value);
            return value;
        });

        Artifact artifact = service.storeBase64("user-1", base64);
        when(repository.findByIdAndUserId(artifact.getId(), "user-1"))
                .thenReturn(Optional.of(artifact));

        assertEquals("user-1", artifact.getUserId());
        assertEquals("image/png", artifact.getMimeType());
        assertTrue(Files.exists(Path.of(artifact.getStoragePath())));
        assertTrue(artifact.getStoragePath().startsWith(tempDir.toString()));

        when(visionService.analyzeImageWithCustomPrompt(any(byte[].class), eq("描述图片")))
                .thenReturn("图片内容");
        String analysis = service.analyze(artifact.getId(), "user-1", "描述图片");

        assertEquals("图片内容", analysis);
        ArgumentCaptor<Artifact> captor = ArgumentCaptor.forClass(Artifact.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("图片内容", captor.getAllValues().get(1).getAnalysis());
    }
}
