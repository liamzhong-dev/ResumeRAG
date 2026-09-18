package interview.guide.modules.notification.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.notification.NotificationProperties;
import interview.guide.modules.notification.dto.NotificationTaskDTO;
import interview.guide.modules.notification.mcp.McpMailService;
import interview.guide.modules.notification.metrics.NotificationMetrics;
import interview.guide.modules.notification.model.NotificationTaskEntity;
import interview.guide.modules.notification.repository.NotificationTaskRepository;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 通知派发：双评分判定 → 幂等建单 → MCP 邮件发送 → 失败退避重投。
 *
 * <p>幂等由两层保证：唯一索引 {@code (biz_type, biz_id)} 是数据库兜底，
 * 建单时先查已有记录且已 SENT 则直接返回，避免重复投递。
 *
 * <p>发送动作放在事务外执行，避免最长数秒的退避重投占着数据库连接。
 */
@Slf4j
@Service
public class NotificationDispatchService {

    private final NotificationProperties properties;
    private final NotificationTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository resumeAnalysisRepository;
    private final ResumeRuleScoringService ruleScoringService;
    private final NotificationDecisionService decisionService;
    private final McpMailService mailService;
    private final NotificationMetrics metrics;
    private final TransactionalExecutor transactionalExecutor;

    public NotificationDispatchService(NotificationProperties properties,
                                       NotificationTaskRepository taskRepository,
                                       ResumeRepository resumeRepository,
                                       ResumeAnalysisRepository resumeAnalysisRepository,
                                       ResumeRuleScoringService ruleScoringService,
                                       NotificationDecisionService decisionService,
                                       McpMailService mailService,
                                       NotificationMetrics metrics,
                                       TransactionalExecutor transactionalExecutor) {
        this.properties = properties;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.resumeAnalysisRepository = resumeAnalysisRepository;
        this.ruleScoringService = ruleScoringService;
        this.decisionService = decisionService;
        this.mailService = mailService;
        this.metrics = metrics;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 简历分析完成后调用：判定是否需要通知 HR。
     *
     * @return 通知任务视图；功能关闭或数据缺失时返回 null，调用方无需处理
     */
    public NotificationTaskDTO dispatchForResume(Long resumeId) {
        if (!properties.isEnabled()) {
            metrics.recordDecision("disabled");
            return null;
        }
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        ResumeAnalysisEntity analysis =
            resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId);
        if (resume == null || analysis == null) {
            log.warn("通知跳过：简历或分析结果不存在，resumeId={}", resumeId);
            return null;
        }

        Integer resumeScore = analysis.getOverallScore();
        int ruleScore = ruleScoringService.score(resume.getResumeText()).total();
        NotificationDecisionService.Decision decision = decisionService.decide(resumeScore, ruleScore);

        NotificationTaskEntity task = transactionalExecutor.call(
            () -> claimTask(resumeId, resumeScore, ruleScore));
        if (task == null) {
            // 已投递成功，幂等跳过
            return null;
        }

        if (!decision.passed()) {
            markTerminal(task, NotificationTaskEntity.STATUS_SKIPPED, null, decision.reason());
            metrics.recordDecision("below_threshold");
            return NotificationTaskDTO.from(task);
        }

        String recipient = properties.getRecipient();
        if (recipient == null || recipient.isBlank()) {
            markTerminal(task, NotificationTaskEntity.STATUS_SKIPPED, null, "未配置收件人");
            metrics.recordDecision("no_recipient");
            return NotificationTaskDTO.from(task);
        }
        if (properties.getMcp().getEndpoint() == null || properties.getMcp().getEndpoint().isBlank()) {
            markTerminal(task, NotificationTaskEntity.STATUS_SKIPPED, null, "未配置 MCP 邮件服务地址");
            metrics.recordDecision("no_endpoint");
            return NotificationTaskDTO.from(task);
        }

        String subject = buildSubject(resume.getOriginalFilename());
        String body = buildBody(resume, analysis, decision.resumeScore(), decision.ruleScore());

        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            McpMailService.SendResult result;
            try {
                result = mailService.send(recipient, subject, body);
            } catch (Exception e) {
                log.error("通知发送异常: resumeId={}, attempt={}, error={}", resumeId, attempt,
                    ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
                result = McpMailService.SendResult.failed(e.getMessage());
            }
            recordAttempt(task, attempt, result);
            if (result.delivered()) {
                markTerminal(task, NotificationTaskEntity.STATUS_SENT, result.messageId(), null);
                metrics.recordDecision("sent");
                log.info("通知已发送: resumeId={}, attempt={}", resumeId, attempt);
                return NotificationTaskDTO.from(task);
            }
            lastError = result.errorMessage();
            if (attempt < maxAttempts) {
                sleepQuietly(properties.getRetryBackoffMs() * (1L << (attempt - 1)));
            }
        }
        markTerminal(task, NotificationTaskEntity.STATUS_FAILED, null, truncate(lastError));
        metrics.recordDecision("failed");
        return NotificationTaskDTO.from(task);
    }

    /** 手动重投失败任务。 */
    public NotificationTaskDTO retry(Long taskId) {
        NotificationTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TASK_NOT_FOUND, "通知任务不存在");
        }
        if (NotificationTaskEntity.STATUS_SENT.equals(task.getStatus())) {
            return NotificationTaskDTO.from(task);
        }
        return dispatchForResume(task.getBizId());
    }

    public List<NotificationTaskDTO> listRecent(int limit) {
        List<NotificationTaskEntity> entities = taskRepository.findTop100ByOrderByIdDesc();
        List<NotificationTaskDTO> result = new ArrayList<>();
        for (NotificationTaskEntity entity : entities) {
            if (result.size() >= Math.min(limit, 100)) {
                break;
            }
            result.add(NotificationTaskDTO.from(entity));
        }
        return result;
    }

    /**
     * 建单或复用已有任务。已 SENT 的任务返回 null 表示不再投递。
     */
    private NotificationTaskEntity claimTask(Long resumeId, Integer resumeScore, int ruleScore) {
        Optional<NotificationTaskEntity> existing = taskRepository.findByBizTypeAndBizId(
            NotificationTaskEntity.BIZ_TYPE_RESUME, resumeId);
        NotificationTaskEntity task;
        if (existing.isPresent()) {
            task = existing.get();
            if (NotificationTaskEntity.STATUS_SENT.equals(task.getStatus())) {
                return null;
            }
        } else {
            task = new NotificationTaskEntity();
            task.setBizType(NotificationTaskEntity.BIZ_TYPE_RESUME);
            task.setBizId(resumeId);
        }
        task.setResumeScore(resumeScore);
        task.setRuleScore(ruleScore);
        task.setThreshold(Math.min(properties.getMinResumeScore(), properties.getMinRuleScore()));
        task.setRecipient(properties.getRecipient());
        task.setStatus(NotificationTaskEntity.STATUS_PENDING);
        task.setAttemptCount(0);
        task.setErrorMessage(null);
        task.setProviderMessageId(null);
        return taskRepository.save(task);
    }

    private void recordAttempt(NotificationTaskEntity task, int attempt,
                               McpMailService.SendResult result) {
        transactionalExecutor.run(() -> {
            NotificationTaskEntity managed = taskRepository.findById(task.getId()).orElse(task);
            managed.setAttemptCount(attempt);
            managed.setLastAttemptAt(LocalDateTime.now());
            managed.setErrorMessage(result.delivered() ? null : truncate(result.errorMessage()));
            taskRepository.save(managed);
        });
    }

    private void markTerminal(NotificationTaskEntity task, String status,
                              String providerMessageId, String error) {
        transactionalExecutor.run(() -> {
            NotificationTaskEntity managed = taskRepository.findById(task.getId()).orElse(task);
            managed.setStatus(status);
            if (providerMessageId != null) {
                managed.setProviderMessageId(truncate(providerMessageId));
            }
            managed.setErrorMessage(error);
            taskRepository.save(managed);
        });
        task.setStatus(status);
        if (providerMessageId != null) {
            task.setProviderMessageId(truncate(providerMessageId));
        }
        task.setErrorMessage(error);
    }

    private String buildSubject(String originalFilename) {
        String name = candidateName(originalFilename);
        return properties.getSubjectTemplate().replace("{name}", name);
    }

    private String candidateName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "未知候选人";
        }
        String name = originalFilename;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replaceAll("[\\-_]", " ").trim();
    }

    private String buildBody(ResumeEntity resume, ResumeAnalysisEntity analysis,
                             int resumeScore, int ruleScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选人：").append(candidateName(resume.getOriginalFilename())).append('\n');
        sb.append("简历 ID：").append(resume.getId()).append('\n');
        sb.append("AI 评分：").append(resumeScore).append(" / 100\n");
        sb.append("规则评分：").append(ruleScore).append(" / 100\n");
        sb.append("上传文件：").append(resume.getOriginalFilename()).append('\n');
        if (analysis.getSummary() != null && !analysis.getSummary().isBlank()) {
            sb.append("\n简历摘要：\n").append(analysis.getSummary()).append('\n');
        }
        sb.append("\n本邮件由 ResumeRAG 在「AI 评分与规则评分同时达到 ")
            .append(properties.getMinResumeScore()).append(" 分」时自动触发。");
        return sb.toString();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
