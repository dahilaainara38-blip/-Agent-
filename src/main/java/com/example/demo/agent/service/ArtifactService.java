package com.example.demo.agent.service;

import com.example.demo.agent.domain.Artifact;
import com.example.demo.agent.repository.ArtifactRepository;
import com.example.demo.vision.VisionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class ArtifactService {

    private static final long MAX_BYTES = 10 * 1024 * 1024;

    private final ArtifactRepository artifactRepository;
    private final VisionService visionService;
    private final Path uploadRoot;

    public ArtifactService(ArtifactRepository artifactRepository,
                           VisionService visionService,
                           @Value("${agent.artifacts.upload-dir:uploads/agent}") String uploadDir) {
        this.artifactRepository = artifactRepository;
        this.visionService = visionService;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public Artifact storeBase64(String userId, String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("图片内容为空");
        }
        String normalized = imageBase64.trim();
        int commaIndex = normalized.indexOf(',');
        if (normalized.startsWith("data:") && commaIndex >= 0) {
            normalized = normalized.substring(commaIndex + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片格式无效，请重新上传");
        }
        return store(userId, bytes, detectMime(bytes), "upload-image");
    }

    public Artifact storeMultipart(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String mimeType = file.getContentType();
            if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                mimeType = detectMime(bytes);
            }
            return store(userId, bytes, mimeType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败：" + e.getMessage(), e);
        }
    }

    private Artifact store(String userId, byte[] bytes, String mimeType, String originalName) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        String type = switch (mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
        String id = "art_" + UUID.randomUUID();
        Path target = uploadRoot.resolve(storageDirectory(userId)).resolve(id + "." + type)
                .normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("图片存储路径无效");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败：" + e.getMessage(), e);
        }

        return artifactRepository.save(Artifact.builder()
                .id(id)
                .userId(userId)
                .storagePath(target.toString())
                .originalFileName(originalName)
                .mimeType(type.equals("jpg") ? "image/jpeg" : mimeType)
                .byteSize(bytes.length)
                .build());
    }

    public Optional<Artifact> findOwned(String artifactId, String userId) {
        return artifactRepository.findByIdAndUserId(artifactId, userId);
    }

    public byte[] readOwnedBytes(String artifactId, String userId) {
        Artifact artifact = findOwned(artifactId, userId)
                .orElseThrow(() -> new IllegalArgumentException("图片不存在或不属于当前用户"));
        try {
            Path path = Path.of(artifact.getStoragePath()).normalize();
            if (!path.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("图片存储路径无效");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("图片读取失败：" + e.getMessage(), e);
        }
    }

    public String readOwnedBase64(String artifactId, String userId) {
        return Base64.getEncoder().encodeToString(readOwnedBytes(artifactId, userId));
    }

    public String analyze(String artifactId, String userId, String prompt) {
        Artifact artifact = findOwned(artifactId, userId)
                .orElseThrow(() -> new IllegalArgumentException("图片不存在或不属于当前用户"));
        String analysis;
        try {
            analysis = visionService.analyzeImageWithCustomPrompt(readOwnedBytes(artifactId, userId), prompt);
        } catch (IOException e) {
            throw new IllegalStateException("图片分析失败：" + e.getMessage(), e);
        }
        artifact.setAnalysis(analysis);
        artifactRepository.save(artifact);
        return analysis;
    }

    private String storageDirectory(String userId) {
        String directory = userId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (directory.isBlank() || directory.equals(".") || directory.equals("..")) {
            throw new IllegalArgumentException("用户存储目录无效");
        }
        return directory;
    }

    private String detectMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("仅支持 JPG、PNG、WEBP 或 GIF 图片");
    }
}
