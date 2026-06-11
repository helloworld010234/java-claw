package com.tinyclaw.ports.observability;

import java.util.Map;

/**
 * 链路追踪报告器端口。
 *
 * <p>抽象底层追踪实现（Micrometer、OpenTelemetry、NoOp 等），
 * 为 Agent 引擎提供 span 创建、属性设置和事件记录能力。</p>
 */
public interface TraceReporter {

    /**
     * 启动一个新的 span。
     *
     * @param name       span 名称，如 "AgentEngine.run"
     * @param attributes 初始属性键值对，可为 null
     * @return span 句柄，用于后续 {@link #endSpan(SpanHandle)} 调用
     */
    SpanHandle startSpan(String name, Map<String, String> attributes);

    /**
     * 结束指定的 span。
     *
     * @param span 要结束的 span 句柄
     */
    void endSpan(SpanHandle span);

    /**
     * 在指定 span 上记录一个事件。
     *
     * @param span       目标 span
     * @param eventName  事件名称，如 "tool.execute"
     * @param attributes 事件属性，可为 null
     */
    void recordEvent(SpanHandle span, String eventName, Map<String, String> attributes);

    /**
     * 在指定 span 上添加或更新属性。
     *
     * @param span       目标 span
     * @param key        属性键
     * @param value      属性值
     */
    void addAttribute(SpanHandle span, String key, String value);

    /**
     * Span 句柄，由具体实现生成。
     *
     * <p>对调用方不透明，仅作为 {@link TraceReporter} 各方法间的标识符。</p>
     */
    interface SpanHandle {
        //  opaque token — implementations attach their native span object
    }
}
