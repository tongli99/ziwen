package com.tong.ziwen.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 查字专才："字问先生"的分身之一，只挂生字表两个查询工具，人设只讲生字。
 *
 * <p>记忆键与全能伴读共用同一套 sessionId 命名空间——分身分掉的是工具和说明书，
 * 不分记忆：同一个窗口里的对话史无论哪个分身接，都在同一段记忆里。</p>
 */
public interface HanziTutor {

    /** 同步问答：阻塞到完整回答才返回。 */
    String chat(@MemoryId String sessionId, @UserMessage String message);

    /** 流式问答：逐 token 回调。 */
    TokenStream stream(@MemoryId String sessionId, @UserMessage String message);
}
