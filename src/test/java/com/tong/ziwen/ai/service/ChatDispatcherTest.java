package com.tong.ziwen.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 编排层分发测试：三种意图各交各的分身、闲聊落兜底，sessionId 与问句原样透传
 * （记忆键不因分诊而变）。分诊台与三个分身全部 mock，不依赖真实模型。
 */
class ChatDispatcherTest {

    /** 三段键取真实形态（家长:娃:窗口），验证的是"不改写、只透传"。 */
    private static final String SESSION_ID = "1:1:1";
    private static final String QUESTION = "'灌'字是哪一课学的？";

    private final RouterAgent routerAgent = Mockito.mock(RouterAgent.class);
    private final PoemTutor poemTutor = Mockito.mock(PoemTutor.class);
    private final HanziTutor hanziTutor = Mockito.mock(HanziTutor.class);
    private final Companion companion = Mockito.mock(Companion.class);

    private ChatDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ChatDispatcher(routerAgent, poemTutor, hanziTutor, companion);
    }

    @Test
    void poemQuestionGoesToPoemTutor() {
        // 背诗意图交给背诗专才：参数与问句原样传，答案原样回
        when(routerAgent.classify(QUESTION)).thenReturn(Intent.POEM);
        when(poemTutor.chat(SESSION_ID, QUESTION)).thenReturn("《望庐山瀑布》，李白写的……");

        String answer = dispatcher.chat(SESSION_ID, QUESTION);

        assertThat(answer).isEqualTo("《望庐山瀑布》，李白写的……");
        verify(poemTutor).chat(SESSION_ID, QUESTION);
        verifyNoInteractions(hanziTutor, companion);
    }

    @Test
    void hanziQuestionGoesToHanziTutor() {
        // 查字意图交给查字专才：其余分身一次都不碰
        when(routerAgent.classify(QUESTION)).thenReturn(Intent.HANZI);
        when(hanziTutor.chat(SESSION_ID, QUESTION)).thenReturn("第 2 课学的，在第 6 页");

        String answer = dispatcher.chat(SESSION_ID, QUESTION);

        assertThat(answer).isEqualTo("第 2 课学的，在第 6 页");
        verify(hanziTutor).chat(SESSION_ID, QUESTION);
        verifyNoInteractions(poemTutor, companion);
    }

    @Test
    void chatQuestionFallsBackToCompanion() {
        // 闲聊（含模型拿不准而归到 CHAT 的句子）落回全工具的伴读——分到 CHAT 错了也接得住
        when(routerAgent.classify(QUESTION)).thenReturn(Intent.CHAT);
        when(companion.chat(SESSION_ID, QUESTION)).thenReturn("宝贝，这个问题我们一起看看……");

        String answer = dispatcher.chat(SESSION_ID, QUESTION);

        assertThat(answer).isEqualTo("宝贝，这个问题我们一起看看……");
        verify(companion).chat(SESSION_ID, QUESTION);
        verifyNoInteractions(poemTutor, hanziTutor);
    }

    @Test
    void classifyFailureFallsBackToCompanion() {
        // 分诊台自身挂了（超时、限流、输出解析失败）也走同一个落点，不放大成整轮失败
        when(routerAgent.classify(QUESTION)).thenThrow(new RuntimeException("模型接口超时"));
        when(companion.chat(SESSION_ID, QUESTION)).thenReturn("宝贝，这个问题我们一起看看……");

        String answer = dispatcher.chat(SESSION_ID, QUESTION);

        assertThat(answer).isEqualTo("宝贝，这个问题我们一起看看……");
        verify(companion).chat(SESSION_ID, QUESTION);
        verifyNoInteractions(poemTutor, hanziTutor);
    }

    @Test
    void streamingFollowsTheSameRoutingAsChat() {
        // 流式与同步共用同一套分发规则：意图决定谁出声，返回的正是对应分身的流
        TokenStream poemStream = Mockito.mock(TokenStream.class);
        TokenStream companionStream = Mockito.mock(TokenStream.class);
        when(routerAgent.classify("背一遍《望庐山瀑布》")).thenReturn(Intent.POEM);
        when(routerAgent.classify("再讲讲刚才那首诗")).thenReturn(Intent.CHAT);
        when(poemTutor.stream(SESSION_ID, "背一遍《望庐山瀑布》")).thenReturn(poemStream);
        when(companion.stream(SESSION_ID, "再讲讲刚才那首诗")).thenReturn(companionStream);

        assertThat(dispatcher.stream(SESSION_ID, "背一遍《望庐山瀑布》")).isSameAs(poemStream);
        assertThat(dispatcher.stream(SESSION_ID, "再讲讲刚才那首诗")).isSameAs(companionStream);
        verifyNoInteractions(hanziTutor);
    }
}
