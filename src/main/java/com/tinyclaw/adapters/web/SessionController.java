package com.tinyclaw.adapters.web;

import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话消息历史查询控制器。
 *
 * <p>GET /api/v1/sessions/{id}/messages 查询指定会话的消息历史。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final MessageRepositoryPort messageRepository;

    public SessionController(MessageRepositoryPort messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * 查询会话消息历史。
     *
     * @param id    会话 ID
     * @param limit 最大返回条数，默认 50
     * @return 消息列表
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<Map<String, Object>>> getSessionMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit) {
        List<AgentMessageDto> messages = messageRepository.findBySessionId(id, limit);
        List<Map<String, Object>> response = messages.stream()
            .map(dto -> Map.<String, Object>of(
                "role", dto.role(),
                "content", dto.content(),
                "createdAt", dto.createdAt()
            ))
            .toList();
        return ResponseEntity.ok(response);
    }
}
