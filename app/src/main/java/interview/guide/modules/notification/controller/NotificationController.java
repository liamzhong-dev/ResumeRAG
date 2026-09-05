package interview.guide.modules.notification.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.notification.NotificationProperties;
import interview.guide.modules.notification.dto.NotificationTaskDTO;
import interview.guide.modules.notification.mcp.McpMailService;
import interview.guide.modules.notification.service.NotificationDispatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知任务查询与人工干预入口。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationDispatchService dispatchService;
    private final McpMailService mailService;
    private final NotificationProperties properties;

    public NotificationController(NotificationDispatchService dispatchService,
                                  McpMailService mailService,
                                  NotificationProperties properties) {
        this.dispatchService = dispatchService;
        this.mailService = mailService;
        this.properties = properties;
    }

    @GetMapping
    public Result<List<NotificationTaskDTO>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(dispatchService.listRecent(limit));
    }

    /** 手动触发某份简历的通知判定与投递（测试与补发用）。 */
    @PostMapping("/resume/{resumeId}/dispatch")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<NotificationTaskDTO> dispatch(@PathVariable Long resumeId) {
        return Result.success(dispatchService.dispatchForResume(resumeId));
    }

    /** 重投失败任务。 */
    @PostMapping("/{id}/retry")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<NotificationTaskDTO> retry(@PathVariable Long id) {
        return Result.success(dispatchService.retry(id));
    }

    /** MCP 邮件服务连通性自检：握手并列工具。 */
    @GetMapping("/mcp/tools")
    public Result<Map<String, Object>> tools() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpoint", properties.getMcp().getEndpoint());
        payload.put("toolName", properties.getMcp().getToolName());
        payload.put("enabled", properties.isEnabled());
        try {
            payload.put("protocolVersion", mailService.handshake());
            payload.put("tools", mailService.availableTools());
            payload.put("reachable", true);
        } catch (Exception e) {
            payload.put("reachable", false);
            payload.put("error", e.getMessage());
        }
        return Result.success(payload);
    }
}
