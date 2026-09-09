package com.example.demo.ai;

import com.example.demo.agent.domain.CareSubject;
import com.example.demo.agent.service.SubjectDirectoryService;
import com.example.demo.care.model.CareRecord;
import com.example.demo.care.service.CareRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Agent 原生护理写工具：建档与记护理记录。
 * 均为 brokered write——由 Tool Broker 拦截生成确认卡，用户确认后才执行。
 */
@Slf4j
@Service
public class AgentCareTools {

    private final SubjectDirectoryService subjectDirectory;
    private final CareRecordService careRecordService;

    public AgentCareTools(SubjectDirectoryService subjectDirectory,
                          CareRecordService careRecordService) {
        this.subjectDirectory = subjectDirectory;
        this.careRecordService = careRecordService;
    }

    @Tool(name = "createCareSubject", description = "为用户创建宠物或植物护理档案（识别出品种后建档、或用户主动要求添加档案时使用）。属于写操作，需要用户确认后执行。")
    public String createCareSubject(
            @ToolParam(description = "档案类型：PET（宠物）或 PLANT（植物）", required = true) String targetType,
            @ToolParam(description = "宠物/植物的名字", required = true) String name,
            @ToolParam(description = "品种，如：英短、绿萝", required = false) String species,
            @ToolParam(description = "细分品种，如：银渐层、黄金葛", required = false) String breed,
            @ToolParam(description = "档案备注：健康状况、来源、习性等", required = false) String profile,
            @AgentContextParam AgentContext context) {
        String userId = context.userId();
        log.info("[Tool] createCareSubject called, userId: {}, type: {}, name: {}", userId, targetType, name);
        if (userId == null || userId.isBlank()) {
            return "请先登录后再创建档案。";
        }
        CareSubject subject = subjectDirectory.create(userId, targetType, name, species, breed, profile);
        return String.format("档案已创建：%s（%s，ID %d）。", subject.getName(),
                subject.getSubjectType() == CareSubject.SubjectType.PET ? "宠物" : "植物", subject.getId());
    }

    @Tool(name = "saveCareRecord", description = "为指定护理对象记录一条护理记录（喂食、浇水、洗护、观察等日常护理动作）。属于写操作，需要用户确认后执行。")
    public String saveCareRecord(
            @ToolParam(description = "目标类型：PET（宠物）或 PLANT（植物）", required = true) String targetType,
            @ToolParam(description = "目标ID（使用当前护理对象的 targetId）", required = true) Long targetId,
            @ToolParam(description = "记录类型：CARE（日常护理）、VITALS（体征）、SYMPTOM（症状观察）、DIAGNOSIS（诊断）、ADVICE（建议）、REMINDER（提醒）", required = true) String recordType,
            @ToolParam(description = "记录内容，如：下午喂了30克猫粮，食欲正常", required = true) String content,
            @AgentContextParam AgentContext context) {
        String userId = context.userId();
        log.info("[Tool] saveCareRecord called, userId: {}, targetId: {}, type: {}", userId, targetId, recordType);
        if (userId == null || userId.isBlank()) {
            return "请先登录后再保存护理记录。";
        }
        CareRecord record = careRecordService.createRecord(CareRecord.builder()
                .userId(userId)
                .targetType(parseTargetType(targetType))
                .targetId(targetId)
                .recordType(parseRecordType(recordType))
                .content(content)
                .build());
        return "护理记录已保存（记录 ID " + record.getId() + "），可在对象时间线中回看。";
    }

    private CareRecord.TargetType parseTargetType(String targetType) {
        try {
            return CareRecord.TargetType.valueOf(targetType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CareRecord.TargetType.PET;
        }
    }

    private CareRecord.RecordType parseRecordType(String recordType) {
        try {
            return CareRecord.RecordType.valueOf(recordType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CareRecord.RecordType.CARE;
        }
    }
}
