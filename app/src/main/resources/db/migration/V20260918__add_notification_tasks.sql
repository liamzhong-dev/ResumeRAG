-- 候选人自动通知任务表（MCP 邮件通知闭环）
-- (biz_type, biz_id) 唯一约束是幂等投递的数据库兜底

CREATE TABLE IF NOT EXISTS notification_tasks (
    id                  BIGSERIAL PRIMARY KEY,
    biz_type            VARCHAR(32)  NOT NULL,
    biz_id              BIGINT       NOT NULL,
    resume_score        INTEGER,
    rule_score          INTEGER,
    threshold           INTEGER      NOT NULL,
    recipient           VARCHAR(255),
    subject             VARCHAR(512),
    status              VARCHAR(16)  NOT NULL,
    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255),
    error_message       VARCHAR(1000),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at     TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_task_biz
    ON notification_tasks (biz_type, biz_id);

CREATE INDEX IF NOT EXISTS idx_notification_task_status
    ON notification_tasks (status);
