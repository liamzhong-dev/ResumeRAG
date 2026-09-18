package interview.guide.modules.notification.dto;

import interview.guide.modules.notification.model.NotificationTaskEntity;

import java.time.LocalDateTime;

/**
 * 通知任务对外视图，不暴露内部实体。
 */
public record NotificationTaskDTO(
    Long id,
    String bizType,
    Long bizId,
    Integer resumeScore,
    Integer ruleScore,
    Integer threshold,
    String recipient,
    String subject,
    String status,
    int attemptCount,
    String providerMessageId,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

    public static NotificationTaskDTO from(NotificationTaskEntity entity) {
        if (entity == null) {
            return null;
        }
        return new NotificationTaskDTO(
            entity.getId(),
            entity.getBizType(),
            entity.getBizId(),
            entity.getResumeScore(),
            entity.getRuleScore(),
            entity.getThreshold(),
            entity.getRecipient(),
            entity.getSubject(),
            entity.getStatus(),
            entity.getAttemptCount(),
            entity.getProviderMessageId(),
            entity.getErrorMessage(),
            entity.getCreatedAt(),
            entity.getUpdatedAt());
    }
}
