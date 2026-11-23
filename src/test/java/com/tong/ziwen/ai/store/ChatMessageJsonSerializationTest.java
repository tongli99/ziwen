package com.tong.ziwen.ai.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 多态消息的序列化往返测试：RedisChatMemoryStore 落库/读库依赖这一性质。
 *
 * <p>ChatMessage 是接口、运行时多子类，能否原样还原全看序列化格式里有没有类型信息。</p>
 */
class ChatMessageJsonSerializationTest {

    @Test
    void messagesSurviveJsonRoundTripWithConcreteSubtypes() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是字问先生"),
                UserMessage.from("灌溉的灌怎么写"),
                AiMessage.from("左边三点水，右边是雚。"));

        String json = ChatMessageSerializer.messagesToJson(messages);

        // JSON 里必须带 type 判别字段——没有它，读回时无法区分该还原成哪个子类
        assertThat(json)
                .contains("\"type\":\"SYSTEM\"")
                .contains("\"type\":\"USER\"")
                .contains("\"type\":\"AI\"");

        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(json);

        assertThat(restored)
                .extracting(message -> message.getClass().getSimpleName())
                .containsExactly("SystemMessage", "UserMessage", "AiMessage");
    }

    /**
     * v0.3 起记忆里会进两种新消息：模型"想调工具"的 AiMessage（内嵌 ToolExecutionRequest）、
     * 工具执行结果 ToolExecutionResultMessage。工具一轮对话要往 Redis 塞这两条，
     * 序列化必须能原样还原——不然带工具的会话重启后记忆就坏了。
     */
    @Test
    void toolCallMessagesSurviveJsonRoundTrip() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-001")
                .name("searchPoems")
                .arguments("{\"query\":\"写瀑布的诗\"}")
                .build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("哪首诗写了瀑布"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "《望庐山瀑布》（唐·李白）日照香炉生紫烟……"));

        String json = ChatMessageSerializer.messagesToJson(messages);

        assertThat(json)
                .contains("\"type\":\"AI\"")
                .contains("\"type\":\"TOOL_EXECUTION_RESULT\"");

        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(json);

        assertThat(restored).hasSize(3);
        AiMessage restoredAi = (AiMessage) restored.get(1);
        assertThat(restoredAi.hasToolExecutionRequests()).isTrue();
        assertThat(restoredAi.toolExecutionRequests().get(0).name()).isEqualTo("searchPoems");
        assertThat(restoredAi.toolExecutionRequests().get(0).arguments()).contains("瀑布");
        ToolExecutionResultMessage restoredResult = (ToolExecutionResultMessage) restored.get(2);
        assertThat(restoredResult.toolName()).isEqualTo("searchPoems");
        assertThat(restoredResult.text()).contains("望庐山瀑布");
    }
}
