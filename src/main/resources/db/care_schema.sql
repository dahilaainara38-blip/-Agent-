-- 护理提醒表
CREATE TABLE IF NOT EXISTS care_reminder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID',
    target_type VARCHAR(20) COMMENT '目标类型：pet/plant',
    target_id BIGINT COMMENT '目标ID',
    reminder_type VARCHAR(50) NOT NULL COMMENT '提醒类型：浇水/施肥/驱虫/疫苗/喂药',
    content TEXT COMMENT '提醒内容',
    metadata JSON COMMENT '扩展字段（药物剂量/疫苗批次等）',
    due_at DATETIME NOT NULL COMMENT '计划时间',
    repeat_rule VARCHAR(20) COMMENT '重复规则：DAILY/WEEKLY/MONTHLY',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态：PENDING/SENT/DONE',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_status_due_at (status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护理提醒表';

-- 识别历史表（用于图片对比）
CREATE TABLE IF NOT EXISTS identify_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID',
    target_id BIGINT COMMENT '关联护理对象ID',
    identify_type VARCHAR(20) NOT NULL COMMENT '识别类型：plant/pet',
    result TEXT COMMENT '识别结果',
    image_url VARCHAR(500) COMMENT '原图路径',
    metadata JSON COMMENT '扩展字段（置信度/特征等）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_type_time (user_id, identify_type, created_at DESC),
    INDEX idx_target_id (target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='识别历史表';

-- 数据迁移：为已有表添加缺失字段（如已存在则跳过）
-- ALTER TABLE identify_history ADD COLUMN target_id BIGINT COMMENT '关联护理对象ID' AFTER user_id;
-- ALTER TABLE identify_history ADD COLUMN image_url VARCHAR(500) COMMENT '原图路径' AFTER result;
-- ALTER TABLE identify_history ADD COLUMN metadata JSON COMMENT '扩展字段' AFTER image_url;
-- ALTER TABLE care_reminder ADD COLUMN metadata JSON COMMENT '扩展字段' AFTER content;

-- 领域事件幂等：同一来源（如确认执行 confirmation_<id>）只允许一条 care_event。
-- MySQL 不支持 CREATE INDEX IF NOT EXISTS：索引已存在或存量数据有重复时本语句报错并被初始化器跳过，属预期行为。
CREATE UNIQUE INDEX idx_care_event_source_event ON care_event (source_event_id);

-- agent_message.content 建表时被生成为 VARCHAR(255)，长回复会 Data truncation；
-- ddl-auto=update 不迁移已有列类型，幂等 ALTER 兜底
ALTER TABLE agent_message MODIFY COLUMN content TEXT NOT NULL;

-- care_subject 建表时 Hibernate 为 source_type 生成了枚举 CHECK 约束，
-- 新增 AGENT 枚举值后约束未随之更新（插入 agent 原生档案会违反约束），幂等删除兜底
ALTER TABLE care_subject DROP CHECK care_subject_chk_1;

-- care_records 历史上被两套命名策略先后加列，下划线组为死列（NOT NULL 无默认，
-- 插入报 1364）。列已不存在时本语句报错被跳过，属预期。
ALTER TABLE care_records
  DROP COLUMN created_at, DROP COLUMN is_completed, DROP COLUMN record_type,
  DROP COLUMN reminder_time, DROP COLUMN target_id, DROP COLUMN target_type, DROP COLUMN user_id;