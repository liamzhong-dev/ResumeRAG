package interview.guide.modules.notification.service;

import interview.guide.modules.notification.NotificationProperties;
import org.springframework.stereotype.Service;

/**
 * 双评分判定：AI 简历评分与规则评分同时过线才允许发送通知。
 *
 * <p>单看 LLM 分的问题在于不可复现：同一份简历重复分析可能得到 88 / 93 两个结果，
 * 直接单点判定会让通知边界抖动。规则分是确定函数，两者取交集后边界稳定。
 */
@Service
public class NotificationDecisionService {

    private final NotificationProperties properties;

    public NotificationDecisionService(NotificationProperties properties) {
        this.properties = properties;
    }

    /** 判定结果。 */
    public record Decision(boolean passed, int resumeScore, int ruleScore,
                           int resumeThreshold, int ruleThreshold, String reason) {

        static Decision blocked(int resumeScore, int ruleScore, int resumeThreshold,
                                int ruleThreshold, String reason) {
            return new Decision(false, resumeScore, ruleScore, resumeThreshold, ruleThreshold, reason);
        }
    }

    /**
     * @param resumeScore AI 简历评分（0-100），来自 ResumeGradingService
     * @param ruleScore   规则评分（0-100），来自 ResumeRuleScoringService
     */
    public Decision decide(Integer resumeScore, Integer ruleScore) {
        int resumeThreshold = properties.getMinResumeScore();
        int ruleThreshold = properties.getMinRuleScore();
        if (resumeScore == null || ruleScore == null) {
            return Decision.blocked(resumeScore == null ? 0 : resumeScore,
                ruleScore == null ? 0 : ruleScore, resumeThreshold, ruleThreshold, "评分缺失");
        }
        if (resumeScore < resumeThreshold) {
            return Decision.blocked(resumeScore, ruleScore, resumeThreshold, ruleThreshold,
                "AI 评分 " + resumeScore + " 低于阈值 " + resumeThreshold);
        }
        if (ruleScore < ruleThreshold) {
            return Decision.blocked(resumeScore, ruleScore, resumeThreshold, ruleThreshold,
                "规则评分 " + ruleScore + " 低于阈值 " + ruleThreshold);
        }
        return new Decision(true, resumeScore, ruleScore, resumeThreshold, ruleThreshold, "双评分达标");
    }
}
