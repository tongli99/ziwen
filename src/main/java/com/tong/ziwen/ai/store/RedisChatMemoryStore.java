package com.tong.ziwen.ai.store;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.config.AiProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;

/**
 * 对话记忆的 Redis 持久化实现：一个会话一个 key，整段消息列表序列化成 JSON 读写。
 *
 * <p>LangChain4j 把记忆拆成"窗口策略 × 存储"两个概念：
 * {@code MessageWindowChatMemory} 管"留最近多少条"，真正存哪由 {@link ChatMemoryStore} 决定。</p>
 *
 * <p><b>为什么用 {@link ChatMessageSerializer}、{@link ChatMessageDeserializer}</b>：{@link ChatMessage} 是接口，运行时是 SystemMessage / UserMessage /
 * AiMessage 等多个子类，直接序列化不会记录"每条是哪个子类"，读回来无法还原。LangChain4j 自带的
 * {@link ChatMessageSerializer} 用"判别字段"方案解决：给每条消息塞一个 {@code "type"}
 * 字段（SYSTEM / USER / AI ...），反序列化时按标签还原子类。所以这里存的是带类型标签的
 * JSON 字符串，而不是对象字节流</p>
 */
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redisTemplate;
    private final AiProperties properties;

    /**
     * 读取整个会话的记忆。
     *
     * <p>key 不存在（新会话或已过期）返回新的空列表。</p>
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(keyOf(memoryId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
    }

    /**
     * 写回整个会话的记忆（窗口裁剪后的全量列表，含 system 消息）。
     *
     * <p>每次写回都重设 TTL（滑动过期）：只要还在聊，记忆就一直续命；
     * 连续 {@code ttl}（默认 7 天）不聊的会话由 Redis 自动清掉，不用自己写清理任务。</p>
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(keyOf(memoryId), json, properties.getMemory().getTtl());
    }

    /** 删除整个会话的记忆（前端"删除会话"时经 ChatController 调用）。 */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(keyOf(memoryId));
    }

    /** 完整 key = 前缀 + sessionId，如 {@code ziwen:chat:memory:1:2:3}（家长 1 的娃 2 的窗口 3）。 */
    private String keyOf(Object memoryId) {
        return properties.getMemory().getKeyPrefix() + memoryId;
    }
}
