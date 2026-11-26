package com.tong.ziwen.ai.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 记忆隔离的读码演示测试：把"隔离就是一个 key 的命名空间"固化成断言。
 *
 * <p>LangChain4j 不存在"隔离引擎"这种东西——{@code ChatMemoryProvider.get(memoryId)}
 * 每个键各造一个窗口，窗口底下同一个 store 也互不串门。v0.1 拿 sessionId 当键、
 * v0.5 换成 {@code parentId:childId}、多窗口后细化到 {@code parentId:childId:windowId}，
 * 框架层一行没改：换键就是换命名空间，这是每个阶段"最小改动面"的证据。</p>
 */
class MemoryIsolationTest {

    /** 测试内自带的内存实现：结构越简单，越能说明隔离只与 key 有关、与实现无关。 */
    static class MapChatMemoryStore implements ChatMemoryStore {

        private final Map<String, List<ChatMessage>> messagesByMemoryId = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return messagesByMemoryId.getOrDefault(String.valueOf(memoryId), new ArrayList<>());
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            messagesByMemoryId.put(String.valueOf(memoryId), new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messagesByMemoryId.remove(String.valueOf(memoryId));
        }
    }

    @Test
    void siblingsNeverSeeEachOthersMessages() {
        // 验收场景：老大老二各聊一轮，切回老大时记忆里只有老大说过的话
        ChatMemoryStore store = new MapChatMemoryStore();
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        provider.get("1:1").add(UserMessage.from("我叫老大"));
        provider.get("1:2").add(UserMessage.from("我叫老二"));

        List<ChatMessage> elderMessages = provider.get("1:1").messages();
        List<ChatMessage> youngerMessages = provider.get("1:2").messages();

        assertThat(textsOf(elderMessages)).containsExactly("我叫老大");
        assertThat(textsOf(youngerMessages)).containsExactly("我叫老二");
    }

    @Test
    void windowsOfSameChildNeverShareSessionIds() {
        // 同娃多窗口：childId 相同也拦不住 windowId 把命名空间分开——
        // 窗口二的回答不知道窗口一说过什么
        ChatMemoryStore store = new MapChatMemoryStore();
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        provider.get("1:1:1").add(UserMessage.from("窗口一的话"));
        provider.get("1:1:2").add(UserMessage.from("窗口二的话"));

        assertThat(textsOf(provider.get("1:1:1").messages())).containsExactly("窗口一的话");
        assertThat(textsOf(provider.get("1:1:2").messages())).containsExactly("窗口二的话");
    }

    @Test
    void familiesNeverShareSessionIds() {
        // 跨家庭场景：两家的娃即使本地编号巧合，拼上家长 id 后也互不可见
        ChatMemoryStore store = new MapChatMemoryStore();
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        provider.get("1:1").add(UserMessage.from("我家老大的话"));
        provider.get("2:1").add(UserMessage.from("别家的话"));

        assertThat(textsOf(provider.get("1:1").messages())).containsExactly("我家老大的话");
        assertThat(textsOf(provider.get("2:1").messages())).containsExactly("别家的话");
    }

    @Test
    void deletingOneSessionIdLeavesOthersIntact() {
        // 清空老大（或删除娃档案）不动老二：删除按 key 精确命中
        ChatMemoryStore store = new MapChatMemoryStore();
        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        provider.get("1:1").add(UserMessage.from("老大的记忆"));
        provider.get("1:2").add(UserMessage.from("老二的记忆"));
        provider.get("1:1").clear();

        assertThat(textsOf(provider.get("1:1").messages())).isEmpty();
        assertThat(textsOf(provider.get("1:2").messages())).containsExactly("老二的记忆");
    }

    private static List<String> textsOf(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> ((UserMessage) message).singleText())
                .toList();
    }
}
