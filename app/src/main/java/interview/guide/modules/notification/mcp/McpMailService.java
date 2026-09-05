package interview.guide.modules.notification.mcp;

import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.notification.NotificationProperties;
import interview.guide.modules.notification.metrics.NotificationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业邮件服务适配层：把「发一封面试邀约邮件」翻译成一次 MCP 工具调用。
 *
 * <p>业务侧只看到 {@code send(...)}，不感知 MCP 的 JSON-RPC 细节；
 * 换邮件服务商时只需要换 MCP Server 与工具名，Java 侧零改动。
 */
@Slf4j
@Service
public class McpMailService {

    private final McpProtocolClient protocolClient;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    public McpMailService(McpProtocolClient protocolClient,
                          NotificationProperties properties,
                          NotificationMetrics metrics) {
        this.protocolClient = protocolClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 发送结果。 */
    public record SendResult(boolean delivered, String messageId, String errorMessage) {

        static SendResult failed(String message) {
            return new SendResult(false, null, message);
        }
    }

    /**
     * 通过 MCP 邮件工具发送通知。
     *
     * @param recipient 收件人
     * @param subject   标题
     * @param body      正文（纯文本）
     */
    public SendResult send(String recipient, String subject, String body) {
        long start = System.nanoTime();
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("to", recipient);
            arguments.put("subject", subject);
            arguments.put("text", body);

            McpProtocolClient.ToolResult result =
                protocolClient.callTool(properties.getMcp().getToolName(), arguments);
            long elapsed = System.nanoTime() - start;

            if (result.isError()) {
                metrics.recordDelivery("failed");
                metrics.recordSendDuration("failed", elapsed);
                log.warn("MCP 邮件工具返回错误态: tool={}, message={}",
                    properties.getMcp().getToolName(), result.text());
                return SendResult.failed(result.text());
            }
            metrics.recordDelivery("sent");
            metrics.recordSendDuration("sent", elapsed);
            return new SendResult(true, result.text(), null);
        } catch (Exception e) {
            metrics.recordDelivery("failed");
            metrics.recordSendDuration("failed", System.nanoTime() - start);
            log.error("MCP 邮件发送异常: error={}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return SendResult.failed(e.getMessage());
        }
    }

    /** 列出 MCP Server 暴露的工具，用于连通性自检。 */
    public List<String> availableTools() {
        return protocolClient.listTools();
    }

    /** MCP 握手，返回协议版本。 */
    public String handshake() {
        return protocolClient.initialize();
    }
}
