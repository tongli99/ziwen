package com.tong.ziwen.ai.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

/**
 * 验证窗口裁剪不会拆散工具调用请求与工具返回消息的配对。
 *
 * <p>孤儿回执（无法配对的工具返回消息）违反 OpenAI 协议
 * （role=tool 消息必须紧跟带 tool_calls 的 assistant 消息），
 * 下一轮请求会直接返回 400。版本 1.19 的 MessageWindowChatMemory 实现
 * 会连带裁掉孤儿回执——本测试将该读码结论固化为受验证的假设，
 * 框架升级后可自动检测行为变化。</p>
 */
class MessageWindowToolPairingTest {

    @Test
    void windowEvictsToolPairTogetherInsteadOfLeavingOrphanResult() {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id("session-1")
                .maxMessages(4)
                .build();

        // 两轮完整的工具对话，加上第三轮，把最早的配对整体挤出窗口
        memory.add(UserMessage.from("哪首诗写了瀑布"));
        memory.add(aiMessageWithRequest("call-1"));
        memory.add(resultMessage("call-1", "《望庐山瀑布》……"));
        memory.add(UserMessage.from("那梅花呢"));
        memory.add(aiMessageWithRequest("call-2"));
        memory.add(resultMessage("call-2", "没查到相关的古诗。"));
        memory.add(UserMessage.from("背一下瀑布那首"));
        memory.add(AiMessage.from("日照香炉生紫烟……"));

        List<ChatMessage> messages = memory.messages();

        // 窗口生效：最早那轮（第一条用户消息）被挤出去了
        assertThat(messages).hasSize(4);
        assertThat(messages).noneMatch(message ->
                message instanceof UserMessage userMessage && userMessage.singleText().contains("哪首诗写了瀑布"));

        // 核心断言：窗口内不允许出现无法配对的回执——每条回执的前一条必须是
        // 携带同 id 调用请求的 AiMessage（按 id 校验，仅校验消息类型不够）
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage result) {
                assertThat(messages.get(i - 1)).isInstanceOf(AiMessage.class);
                AiMessage requestHolder = (AiMessage) messages.get(i - 1);
                assertThat(requestHolder.toolExecutionRequests())
                        .anySatisfy(request -> assertThat(request.id()).isEqualTo(result.id()));
            }
        }
    }

    private static AiMessage aiMessageWithRequest(String callId) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id(callId)
                .name("searchPoems")
                .arguments("{\"query\":\"写瀑布的诗\"}")
                .build());
    }

    private static ToolExecutionResultMessage resultMessage(String callId, String text) {
        return ToolExecutionResultMessage.from(ToolExecutionRequest.builder()
                .id(callId)
                .name("searchPoems")
                .arguments("{\"query\":\"写瀑布的诗\"}")
                .build(), text);
    }
}
