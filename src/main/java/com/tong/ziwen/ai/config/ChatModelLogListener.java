package com.tong.ziwen.ai.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 把每次调大模型的完整进出打印到日志，用于观察 AiServices 代理实际发给模型的是什么。
 *
 * <p>LangChain4j 的 {@link ChatModelListener} 在模型调用前后回调：{@code onRequest}
 * 拿到框架拼装好的完整 {@code ChatRequest}（system prompt + 全部历史消息），
 * {@code onResponse} 拿到模型返回的 {@code ChatResponse}。相比抓 HTTP 报文，这里看到的
 * 是框架组装后的语义层消息，正好回答"这个分身到底对模型说了什么"。</p>
 *
 * <p>同步与流式模型共用本监听器（1.19 统一了监听接口）；流式时 {@code onResponse}
 * 收到的是汇总后的完整回答，不是逐 token 片段。</p>
 */
@Slf4j
public class ChatModelLogListener implements ChatModelListener {

    @Override
    public void onRequest(ChatModelRequestContext context) {
        log.debug("===== LLM REQUEST =====");
        for (ChatMessage message : context.chatRequest().messages()) {
            log.debug("[{}] {}", message.type(), message);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        log.debug("===== LLM RESPONSE =====");
        log.debug("text = {}", context.chatResponse().aiMessage().text());
        log.debug("usage = {}", context.chatResponse().tokenUsage());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        log.error("===== LLM ERROR =====", context.error());
    }
}
