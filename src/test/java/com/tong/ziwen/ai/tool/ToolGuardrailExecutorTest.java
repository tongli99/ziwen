package com.tong.ziwen.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tong.ziwen.ai.config.AiProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * 工具护栏执行器测试：调用限次（超限返回劝停文本而非抛出异常）、状态推送、按轮清零。
 * 委托对象使用假实现，全程不启动 Spring、不访问真实检索——护栏作为代理，其行为可离线验证。
 */
class ToolGuardrailExecutorTest {

    private static final ToolExecutionRequest REQUEST = ToolExecutionRequest.builder()
            .id("call-001")
            .name("searchPoems")
            .arguments("{\"query\":\"写瀑布的诗\"}")
            .build();

    /** 记录被放行的调用次数并返回固定结果的假执行器：验证护栏放行时会原样返回委托结果。 */
    private static final class RecordingDelegate implements ToolExecutor {
        int calls;

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            calls++;
            return "委托结果";
        }
    }

    /** 记录推送内容的假中枢：真实 ToolStatusHub 会向 SSE 连接写入，单测环境没有连接。 */
    private static final class RecordingStatusHub extends ToolStatusHub {
        final List<String> pushed = new ArrayList<>();

        @Override
        public void pushStatus(String sessionId, String statusText) {
            pushed.add(sessionId + "|" + statusText);
        }
    }

    private RecordingDelegate delegate = new RecordingDelegate();
    private RecordingStatusHub statusHub = new RecordingStatusHub();
    private ToolCallLimiter limiter = new ToolCallLimiter(propertiesWithMaxCallsPerTurn(2));
    private ToolGuardrailExecutor executor = new ToolGuardrailExecutor(
            delegate, "字问先生正在翻古诗书…", limiter, statusHub);

    private static AiProperties propertiesWithMaxCallsPerTurn(int maxCallsPerTurn) {
        AiProperties properties = new AiProperties();
        properties.getTool().setMaxCallsPerTurn(maxCallsPerTurn);
        return properties;
    }

    @Test
    void allowedCallsGoThroughAndPushWorkingStatus() {
        assertThat(executor.execute(REQUEST, "session-1")).isEqualTo("委托结果");
        assertThat(executor.execute(REQUEST, "session-1")).isEqualTo("委托结果");
        assertThat(delegate.calls).isEqualTo(2);
        // 每次放行都推送一条"正在翻古诗书…"，这是等待期间前端展示的状态文案
        assertThat(statusHub.pushed).containsExactly(
                "session-1|字问先生正在翻古诗书…",
                "session-1|字问先生正在翻古诗书…");
    }

    @Test
    void overLimitReturnsStopAdviceInsteadOfCallingTool() {
        executor.execute(REQUEST, "session-1");
        executor.execute(REQUEST, "session-1");

        String third = executor.execute(REQUEST, "session-1");

        // 超限时返回的是给模型的劝停指令：工具未执行，状态也未推送（此时没有正在进行的查询）
        assertThat(third).contains("上限").contains("不要再调用工具");
        assertThat(delegate.calls).isEqualTo(2);
        assertThat(statusHub.pushed).hasSize(2);
    }

    @Test
    void resetStartsANewTurn() {
        executor.execute(REQUEST, "session-1");
        executor.execute(REQUEST, "session-1");
        assertThat(executor.execute(REQUEST, "session-1")).contains("上限");

        // 新一轮对话：入口清零后同样的调用重新放行——限制范围是单轮，而非整个会话生命周期
        limiter.reset("session-1");
        assertThat(executor.execute(REQUEST, "session-1")).isEqualTo("委托结果");
        assertThat(delegate.calls).isEqualTo(3);
    }

    @Test
    void countsArePerSession() {
        executor.execute(REQUEST, "session-1");
        executor.execute(REQUEST, "session-1");

        // session-1 达到上限不影响 session-2：两个会话各自独立计数
        assertThat(executor.execute(REQUEST, "session-2")).isEqualTo("委托结果");
    }
}
