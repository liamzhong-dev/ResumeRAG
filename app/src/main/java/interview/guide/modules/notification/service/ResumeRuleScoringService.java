package interview.guide.modules.notification.service;

import interview.guide.modules.notification.NotificationProperties;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历规则评分：与 LLM 打分并行的第二路评分。
 *
 * <p>存在的意义是给 AI 评分加一道确定性校验：LLM 对同一份简历多次打分会有漂移，
 * 规则分则完全可复现。两路都过线才发通知，避免因为模型抖动误发面试邀约。
 *
 * <p>评分维度与权重全部来自 {@code app.notification.rule}，可按岗位调整而不改代码。
 */
@Service
public class ResumeRuleScoringService {

    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d+)\\s*年");

    private final NotificationProperties properties;

    public ResumeRuleScoringService(NotificationProperties properties) {
        this.properties = properties;
    }

    /** 评分明细。 */
    public record ScoreBreakdown(int total, int skill, int experience, int education,
                                 int project, int structure) {}

    public ScoreBreakdown score(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return new ScoreBreakdown(0, 0, 0, 0, 0, 0);
        }
        String haystack = resumeText.toLowerCase(Locale.ROOT);
        NotificationProperties.Rule rule = properties.getRule();

        int skill = scoreSkill(haystack, rule);
        int experience = scoreExperience(resumeText, rule);
        int education = scoreEducation(haystack, rule);
        int project = scoreProject(haystack, rule);
        int structure = scoreStructure(haystack, rule);

        int total = Math.min(100, skill + experience + education + project + structure);
        return new ScoreBreakdown(total, skill, experience, education, project, structure);
    }

    /** 技能关键词命中率。 */
    private int scoreSkill(String haystack, NotificationProperties.Rule rule) {
        if (rule.getSkillKeywords() == null || rule.getSkillKeywords().isEmpty()) {
            return 0;
        }
        long hit = rule.getSkillKeywords().stream()
            .filter(keyword -> keyword != null && !keyword.isBlank())
            .filter(keyword -> haystack.contains(keyword.toLowerCase(Locale.ROOT)))
            .count();
        return scale(hit, rule.getSkillKeywords().size(), rule.getSkillMaxScore());
    }

    /** 工作年限，取文本中出现的最大年数。 */
    private int scoreExperience(String resumeText, NotificationProperties.Rule rule) {
        Matcher matcher = YEARS_PATTERN.matcher(resumeText);
        int maxYears = 0;
        while (matcher.find()) {
            try {
                int years = Integer.parseInt(matcher.group(1));
                // 1998 年这类年份不该算作工作年限
                if (years > 0 && years < 60) {
                    maxYears = Math.max(maxYears, years);
                }
            } catch (NumberFormatException ignored) {
                // 忽略超长数字
            }
        }
        return scale(Math.min(maxYears, rule.getFullExperienceYears()),
            rule.getFullExperienceYears(), rule.getExperienceMaxScore());
    }

    /** 学历：博士 > 硕士 > 本科 > 大专。 */
    private int scoreEducation(String haystack, NotificationProperties.Rule rule) {
        double ratio;
        if (haystack.contains("博士")) {
            ratio = 1.0;
        } else if (haystack.contains("硕士") || haystack.contains("研究生")) {
            ratio = 0.85;
        } else if (haystack.contains("本科") || haystack.contains("学士")) {
            ratio = 0.7;
        } else if (haystack.contains("大专") || haystack.contains("专科")) {
            ratio = 0.45;
        } else {
            ratio = 0.0;
        }
        return (int) Math.round(ratio * rule.getEducationMaxScore());
    }

    /** 项目经历密度。 */
    private int scoreProject(String haystack, NotificationProperties.Rule rule) {
        int count = countOccurrences(haystack, "项目");
        return scale(Math.min(count, rule.getFullProjectCount()),
            rule.getFullProjectCount(), rule.getProjectMaxScore());
    }

    /** 结构完整度：必需章节是否齐全。 */
    private int scoreStructure(String haystack, NotificationProperties.Rule rule) {
        if (rule.getRequiredSections() == null || rule.getRequiredSections().isEmpty()) {
            return 0;
        }
        long present = rule.getRequiredSections().stream()
            .filter(section -> section != null && haystack.contains(section.toLowerCase(Locale.ROOT)))
            .count();
        return scale(present, rule.getRequiredSections().size(), rule.getStructureMaxScore());
    }

    private int scale(long value, long full, int maxScore) {
        if (full <= 0) {
            return 0;
        }
        return (int) Math.round((double) value / full * maxScore);
    }

    private int countOccurrences(String haystack, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = haystack.indexOf(token, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + token.length();
        }
    }
}
