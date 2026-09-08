package interview.guide.modules.notification.service;

import interview.guide.modules.notification.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("双评分通知判定")
class NotificationDecisionServiceTest {

    private NotificationDecisionService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMinResumeScore(90);
        properties.setMinRuleScore(90);
        service = new NotificationDecisionService(properties);
    }

    @Test
    @DisplayName("两路评分同时达标才放行")
    void passesOnlyWhenBothScoresMeetThreshold() {
        assertThat(service.decide(95, 92).passed()).isTrue();
        assertThat(service.decide(90, 90).passed()).isTrue();
    }

    @Test
    @DisplayName("AI 评分不足即拦截")
    void blocksWhenResumeScoreBelowThreshold() {
        NotificationDecisionService.Decision decision = service.decide(88, 95);
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reason()).contains("AI 评分");
    }

    @Test
    @DisplayName("规则评分不足即拦截")
    void blocksWhenRuleScoreBelowThreshold() {
        NotificationDecisionService.Decision decision = service.decide(95, 70);
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reason()).contains("规则评分");
    }

    @Test
    @DisplayName("评分为空时不放行")
    void blocksWhenScoreMissing() {
        assertThat(service.decide(null, 95).passed()).isFalse();
        assertThat(service.decide(95, null).passed()).isFalse();
    }
}
