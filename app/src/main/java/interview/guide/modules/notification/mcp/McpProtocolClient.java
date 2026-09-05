package interview.guide.modules.notification.mcp;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.notification.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP（Model Context Protocol）客户端：JSON-RPC 2.0 over Streamable HTTP。
 *
 * <p>只实现本项目需要的三个方法：initialize、tools/list、tools/call。
 * 序列化与反序列化统一走 Jackson，报文结构用 {@code Map<String, Object>} 承载，
 * 避免为每种工具结果定义 DTO。
 *
 * <p>握手用的 session 信息由 MCP Server 通过 {@code Mcp-Session-Id} 响应头下发，
 * 这里按无状态模式处理：每次调用独立建连，不依赖长会话，失败即可重投。
 */
@Slf4j
@Component
public class McpProtocolClient {

    private final RestClient restClient;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();

    public McpProtocolClient(NotificationProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        NotificationProperties.Mcp mcp = properties.getMcp();
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(mcp.getConnectTimeoutMs(), 1)))
            .build();
        this.restClient = RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
            .defaultHeader("MCP-Protocol-Version", mcp.getProtocolVersion())
            .build();
    }

    /** tools/call 的返回结果。 */
    public record ToolResult(boolean isError, String text, Map<String, Object> raw) {

        static ToolResult failure(String text) {
            return new ToolResult(true, text, Map.of());
        }
    }

    /**
     * MCP 握手，返回服务端确认的协议版本。
     */
    public String initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", properties.getMcp().getProtocolVersion());
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
            "name", properties.getMcp().getClientName(),
            "version", properties.getMcp().getClientVersion()));
        Map<String, Object> result = send("initialize", params);
        Object version = result == null ? null : result.get("protocolVersion");
        return version == null ? properties.getMcp().getProtocolVersion() : String.valueOf(version);
    }

    /**
     * 列出 MCP Server 暴露的工具名，用于启动时校验邮件工具是否可用。
     */
    public List<String> listTools() {
        Map<String, Object> result = send("tools/list", Map.of());
        List<String> names = new ArrayList<>();
        if (result != null && result.get("tools") instanceof List<?> tools) {
            for (Object item : tools) {
                if (item instanceof Map<?, ?> tool && tool.get("name") != null) {
                    names.add(String.valueOf(tool.get("name")));
                }
            }
        }
        return names;
    }

    /**
     * 调用指定工具。
     *
     * @param toolName  工具名
     * @param arguments 工具入参，键名由 MCP Server 的工具定义决定
     */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Map<String, Object> result = send("tools/call", params);
        if (result == null) {
            return ToolResult.failure("MCP 返回空结果");
        }
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        String text = extractText(result.get("content"));
        return new ToolResult(isError, text, result);
    }

    private String extractText(Object content) {
        if (!(content instanceof List<?> items)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            if (item instanceof Map<?, ?> block && "text".equals(block.get("type"))) {
                Object text = block.get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 发送一条 JSON-RPC 2.0 请求并返回 result 节点。
     */
    private Map<String, Object> send(String method, Map<String, Object> params) {
        long id = sequence.incrementAndGet();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 请求序列化失败");
        }

        String body;
        try {
            body = restClient.post()
                .uri(properties.getMcp().getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.error("MCP 调用失败: method={}, error={}", method,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.MCP_CALL_FAILED, "MCP 服务调用失败：" + method);
        }

        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.MCP_CALL_FAILED, "MCP 服务返回空响应：" + method);
        }

        Map<String, Object> response;
        try {
            response = readMap(body);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.MCP_CALL_FAILED, "MCP 响应解析失败：" + method);
        }

        if (response.get("error") instanceof Map<?, ?> error) {
            Object message = error.get("message");
            log.error("MCP 返回错误: method={}, code={}, message={}",
                method, error.get("code"), message);
            throw new BusinessException(ErrorCode.MCP_CALL_FAILED,
                "MCP 返回错误：" + (message == null ? "未知错误" : message));
        }

        Object result = response.get("result");
        return result instanceof Map<?, ?> map ? cast(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String body) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }
}
