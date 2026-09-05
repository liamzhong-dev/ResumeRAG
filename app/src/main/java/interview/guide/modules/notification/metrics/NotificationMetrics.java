package interview.guide.modules.notification.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通知模块业务指标。
 *
 * <p>固定 Meter 名称与低基数标签，禁止把收件人、邮件正文、异常堆栈塞进标签。
 */
@Component
public class NotificationMetrics {

    public static final String DECISION = "app.notification.decision";
    public static final String DELIVERY = "app.notification.delivery";
    public static final String SEND_DURATION = "app.notification.send.duration";

    private final MeterRegistry registry;

    public NotificationMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider != null ? registryProvider.getIfAvailable() : null;
    }

    /** 双评分判定结果：outcome=sent|failed|below_threshold|disabled|no_recipient|no_endpoint */
    public void recordDecision(String outcome) {
        if (registry != null) {
            registry.counter(DECISION, "outcome", outcome).increment();
        }
    }

    /** MCP 邮件投递结果：result=sent|failed */
    public void recordDelivery(String result) {
        if (registry != null) {
            registry.counter(DELIVERY, "result", result).increment();
        }
    }

    /** 单次 MCP 调用耗时：result=sent|failed */
    public void recordSendDuration(String result, long durationNanos) {
        if (registry != null) {
            Timer.builder(SEND_DURATION)
                .tag("result", result)
                .register(registry)
                .record(Duration.ofNanos(durationNanos));
        }
    }
}
