package interview.guide.modules.notification.mcp;

import interview.guide.modules.notification.NotificationProperties;
import interview.guide.modules.notification.metrics.NotificationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 邮件适配层")
class McpMailServiceTest {

    @Mock
    private McpProtocolClient protocolClient;
    @Mock
    private NotificationMetrics metrics;

    private McpMailService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMcp().setToolName("send_email");
        service = new McpMailService(protocolClient, properties, metrics);
    }

    @Test
    @DisplayName("工具返回成功态即视为送达，并回传回执")
    void deliveredWhenToolSucceeds() {
        when(protocolClient.callTool(eq("send_email"), any()))
            .thenReturn(new McpProtocolClient.ToolResult(false, "message-1", Map.of()));

        McpMailService.SendResult result = service.send("hr@example.com", "标题", "正文");

        assertThat(result.delivered()).isTrue();
        assertThat(result.messageId()).isEqualTo("message-1");
        verify(metrics).recordDelivery("sent");
    }

    @Test
    @DisplayName("工具返回错误态不算送达")
    void notDeliveredWhenToolReportsError() {
        when(protocolClient.callTool(eq("send_email"), any()))
            .thenReturn(new McpProtocolClient.ToolResult(true, "收件人不存在", Map.of()));

        McpMailService.SendResult result = service.send("hr@example.com", "标题", "正文");

        assertThat(result.delivered()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("收件人不存在");
        verify(metrics).recordDelivery("failed");
    }

    @Test
    @DisplayName("协议层异常降级为未送达，不向调用方抛出")
    void exceptionDegradesToFailure() {
        when(protocolClient.callTool(eq("send_email"), any()))
            .thenThrow(new IllegalStateException("连接超时"));

        McpMailService.SendResult result = service.send("hr@example.com", "标题", "正文");

        assertThat(result.delivered()).isFalse();
        verify(metrics).recordDelivery("failed");
    }
}
