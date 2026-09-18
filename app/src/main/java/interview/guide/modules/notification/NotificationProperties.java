package interview.guide.modules.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 候选人通知配置：双评分阈值、MCP 邮件服务地址、规则评分权重。
 *
 * <p>通知默认关闭（enabled=false），未配置 MCP 邮件服务地址时所有发送动作直接降级为 SKIPPED，
 * 不影响简历分析主流程。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    /** 通知总开关。 */
    private boolean enabled = false;

    /** AI 简历评分门槛（0-100）。 */
    private int minResumeScore = 90;

    /** 规则评分门槛（0-100）。 */
    private int minRuleScore = 90;

    /** 默认收件人（HR 邮箱），为空则跳过发送。 */
    private String recipient = "";

    /** 邮件标题模板，{name} 会被替换为候选人姓名。 */
    private String subjectTemplate = "【ResumeRAG】候选人 {name} 已进入面试邀约名单";

    /** 单条通知最大尝试次数（含首次）。 */
    private int maxAttempts = 3;

    /** 失败重投退避基数（毫秒），实际间隔为 backoff * 2^(attempt-1)。 */
    private long retryBackoffMs = 2000;

    private Mcp mcp = new Mcp();

    private Rule rule = new Rule();

    @Data
    public static class Mcp {

        /** MCP Server 的 Streamable HTTP 端点，例如 http://localhost:8931/mcp。 */
        private String endpoint = "";

        /** MCP 暴露的邮件工具名。 */
        private String toolName = "send_email";

        /** 握手时声明的协议版本。 */
        private String protocolVersion = "2025-06-18";

        private String clientName = "resumerag";

        private String clientVersion = "1.0.0";

        private long connectTimeoutMs = 3000;

        private long readTimeoutMs = 15000;
    }

    @Data
    public static class Rule {

        /** 技能关键词命中表。 */
        private List<String> skillKeywords = List.of(
            "Java", "Spring", "Spring Boot", "Redis", "MySQL", "Kafka", "JVM", "分布式", "微服务", "RAG");

        /** 结构完整性要求的章节关键字。 */
        private List<String> requiredSections = List.of("教育", "工作", "项目", "技能");

        private int skillMaxScore = 30;
        private int experienceMaxScore = 20;
        private int educationMaxScore = 15;
        private int projectMaxScore = 20;
        private int structureMaxScore = 15;

        /** 工作年限达到该值即拿满 experienceMaxScore。 */
        private int fullExperienceYears = 10;

        /** 项目经历计数达到该值即拿满 projectMaxScore。 */
        private int fullProjectCount = 4;
    }
}
