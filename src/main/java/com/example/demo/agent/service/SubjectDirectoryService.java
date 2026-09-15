package com.example.demo.agent.service;

import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.repository.CareSubjectRepository;
import com.example.demo.care.model.CareTarget;
import com.example.demo.care.repository.CareTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class SubjectDirectoryService {

    private final CareSubjectRepository subjectRepository;
    private final CareTargetRepository careTargetRepository;

    public SubjectDirectoryService(CareSubjectRepository subjectRepository,
                                   CareTargetRepository careTargetRepository) {
        this.subjectRepository = subjectRepository;
        this.careTargetRepository = careTargetRepository;
    }

    @Transactional
    public List<CareSubject> subjects(String userId) {
        List<CareSubject> subjects = subjectRepository
                .findByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId);
        if (!subjects.isEmpty()) {
            return subjects;
        }

        subjects = careTargetRepository.findByUserId(userId).stream()
                .map(this::fromCareTarget)
                .map(subjectRepository::save)
                .sorted(Comparator.comparing(CareSubject::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return subjects;
    }

    @Transactional
    public Optional<ResolvedSubject> resolve(String userId, String subjectType, Long subjectId) {
        List<CareSubject> subjects = subjects(userId);
        if (subjectId != null) {
            return subjects.stream()
                    .filter(subject -> subject.getId().equals(subjectId)
                            || (subject.getSourceId() != null && subject.getSourceId().equals(subjectId)))
                    .filter(subject -> subjectType == null || subjectType.isBlank()
                            || subject.getSubjectType().name().equalsIgnoreCase(subjectType))
                    .findFirst()
                    .map(this::resolve);
        }
        if (subjectType == null || subjectType.isBlank()) {
            return subjects.stream().findFirst().map(this::resolve);
        }

        try {
            CareSubject.SubjectType type = CareSubject.SubjectType.valueOf(subjectType.trim().toUpperCase());
            return subjects.stream()
                    .filter(subject -> subject.getSubjectType() == type)
                    .findFirst()
                    .map(this::resolve);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("护理对象类型仅支持 PET 或 PLANT");
        }
    }

    private ResolvedSubject resolve(CareSubject subject) {
        Long effectiveSubjectId = subject.getSourceType() == CareSubject.SourceType.CARE_TARGET
                && subject.getSourceId() != null ? subject.getSourceId() : subject.getId();
        return new ResolvedSubject(subject, effectiveSubjectId);
    }

    private CareSubject fromCareTarget(CareTarget target) {
        return CareSubject.builder()
                .userId(target.getUserId())
                .subjectType(CareSubject.SubjectType.valueOf(target.getType().name()))
                .name(target.getName())
                .species(target.getSpecies())
                .breed(target.getBreed())
                .profile(target.getDescription())
                .sourceType(CareSubject.SourceType.CARE_TARGET)
                .sourceId(target.getId())
                .active(true)
                .build();
    }

    @Transactional
    public CareSubject create(String userId, String subjectType, String name,
                              String species, String breed, String profile) {
        CareSubject.SubjectType type = parseType(subjectType);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("档案名称不能为空");
        }
        return subjectRepository.save(CareSubject.builder()
                .userId(userId)
                .subjectType(type)
                .name(name.trim())
                .species(blankToNull(species))
                .breed(blankToNull(breed))
                .profile(blankToNull(profile))
                .sourceType(CareSubject.SourceType.AGENT)
                .active(true)
                .build());
    }

    /**
     * 软删 agent 侧档案视图；CARE_TARGET 派生的档案同时删除其源行，
     * 否则最后一条 active 视图被删空后 subjects() 会从 CareTarget 重新归一化导致"复活"。
     */
    @Transactional
    public CareSubject softDelete(String userId, Long subjectId) {
        CareSubject subject = subjectRepository.findByIdAndUserId(subjectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("档案不存在或不属于当前用户"));
        subject.setActive(false);
        if (subject.getSourceType() == CareSubject.SourceType.CARE_TARGET
                && subject.getSourceId() != null) {
            careTargetRepository.findById(subject.getSourceId())
                    .ifPresent(target -> careTargetRepository.delete(target));
        }
        return subjectRepository.save(subject);
    }

    private CareSubject.SubjectType parseType(String subjectType) {
        if (subjectType == null || subjectType.isBlank()) {
            throw new IllegalArgumentException("档案类型不能为空");
        }
        try {
            return CareSubject.SubjectType.valueOf(subjectType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("档案类型仅支持 PET 或 PLANT");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ResolvedSubject(CareSubject subject, Long effectiveSubjectId) {
    }
}
