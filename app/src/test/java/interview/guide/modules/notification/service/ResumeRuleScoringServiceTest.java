package interview.guide.modules.notification.service;

import interview.guide.modules.notification.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("简历规则评分")
class ResumeRuleScoringServiceTest {

    private ResumeRuleScoringService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.getRule().setSkillKeywords(java.util.List.of("Java", "Redis", "Kafka"));
        properties.getRule().setRequiredSections(java.util.List.of("教育", "项目"));
        properties.getRule().setSkillMaxScore(30);
        properties.getRule().setExperienceMaxScore(20);
        properties.getRule().setEducationMaxScore(15);
        properties.getRule().setProjectMaxScore(20);
        properties.getRule().setStructureMaxScore(15);
        properties.getRule().setFullExperienceYears(10);
        properties.getRule().setFullProjectCount(4);
        service = new ResumeRuleScoringService(properties);
    }

    @Test
    @DisplayName("空文本得 0 分")
    void blankTextScoresZero() {
        assertThat(service.score(null).total()).isZero();
        assertThat(service.score("   ").total()).isZero();
    }

    @Test
    @DisplayName("技能关键词按命中率计分")
    void skillScoreIsProportional() {
        ResumeRuleScoringService.ScoreBreakdown all =
            service.score("熟练掌握 Java 与 Redis，熟悉 Kafka");
        assertThat(all.skill()).isEqualTo(30);

        ResumeRuleScoringService.ScoreBreakdown none = service.score("熟悉 Go 与 Python");
        assertThat(none.skill()).isZero();
    }

    @Test
    @DisplayName("工作年限按文本中最大年数计分")
    void experienceUsesMaxYears() {
        ResumeRuleScoringService.ScoreBreakdown breakdown =
            service.score("具有 5 年 Java 开发经验，2019 年本科毕业");
        assertThat(breakdown.experience()).isEqualTo(10);
    }

    @Test
    @DisplayName("学历与结构章节命中计分")
    void educationAndStructureScored() {
        ResumeRuleScoringService.ScoreBreakdown breakdown =
            service.score("硕士学历，教育经历：xxx，项目经历：yyy");
        assertThat(breakdown.education()).isEqualTo(13);
        assertThat(breakdown.structure()).isEqualTo(15);
    }

    @Test
    @DisplayName("总分封顶 100")
    void totalIsCapped() {
        String rich = "博士，20 年经验，精通 Java Redis Kafka 分布式 微服务 RAG，"
            + "教育经历 工作经历 项目经历 项目一 项目二 项目三 项目四 技能清单";
        assertThat(service.score(rich).total()).isLessThanOrEqualTo(100);
    }
}
