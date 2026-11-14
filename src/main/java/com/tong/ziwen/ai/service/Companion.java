package com.tong.ziwen.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * "字问先生"伴读伙伴接口，由 AiServices 动态代理实现。
 *
 * <p>同一个接口可同时提供同步与流式两种返回。</p>
 */
public interface Companion {

    /** 同步问答：阻塞到完整回答才返回。 */
    String chat(@MemoryId String sessionId, @UserMessage String message);

    /** 流式问答：逐 token 回调。 */
    TokenStream stream(@MemoryId String sessionId, @UserMessage String message);
}
