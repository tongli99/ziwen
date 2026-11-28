package com.tong.ziwen.ai.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tong.ziwen.ai.config.AiConfig;
import com.tong.ziwen.ai.config.AiProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;

/**
 * 分身切换时 system message 位置的回归测试。
 *
 * <p>每轮调用框架都把当前分身的 system message 写进记忆；LangChain4j 1.19 的
 * {@code MessageWindowChatMemory} 默认把新的 system message 追加到列表末尾，
 * 多分身共用记忆后它会夹在历史中间。本测试走 AiConfig 里真实的 chatMemoryProvider 装配，
 * 锁定 {@code alwaysKeepSystemMessageFirst(true)} 的效果：换分身只换内容、不挪位置、不占窗口。</p>
 */
class SystemMessagePositionTest {

    @Test
    void switchingAgentReplacesSystemMessageInPlace() {
        // Redis 用 mock 顶替：只负责把窗口写回的列表存下来、下次原样读出
        List<ChatMessage> stored = new ArrayList<>();
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenAnswer(invocation -> new ArrayList<>(stored));
        doAnswer(invocation -> {
            stored.clear();
            stored.addAll((List<ChatMessage>) invocation.getArgument(1));
            return null;
        }).when(store).updateMessages(any(), any());

        ChatMemoryProvider provider = new AiConfig(new AiProperties()).chatMemoryProvider(store);
        ChatMemory memory = provider.get("1:1:1");

        // 验收场景：同一窗口第一轮背诗、第二轮查字，两个分身的说明书不同
        memory.add(SystemMessage.from("背诗分身的说明书"));
        memory.add(UserMessage.from("背一遍《望庐山瀑布》"));
        memory.add(AiMessage.from("日照香炉生紫烟……"));
        memory.add(SystemMessage.from("查字分身的说明书"));
        memory.add(UserMessage.from("灌字是哪一课学的"));

        List<ChatMessage> messages = memory.messages();

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isEqualTo(SystemMessage.from("查字分身的说明书"));
        assertThat(messages.stream().filter(SystemMessage.class::isInstance).count()).isEqualTo(1);
    }
}
