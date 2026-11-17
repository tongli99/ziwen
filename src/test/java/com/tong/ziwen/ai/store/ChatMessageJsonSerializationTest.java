package com.tong.ziwen.ai.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
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
}
