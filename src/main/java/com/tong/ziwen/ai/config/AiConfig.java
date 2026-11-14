package com.tong.ziwen.ai.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.tong.ziwen.ai.service.Companion;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * LangChain4j Bean 装配。
 *
 * <p>对话模型走 OpenAI 兼容协议（OpenAiChatModel / OpenAiStreamingChatModel），
 * 通过 base-url 指向 DeepSeek 等任意兼容提供方。流式与同步使用同一套参数。</p>
 */
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AiProperties properties;

    @Bean
    public ChatModel chatModel() {
        AiProperties.Chat chat = properties.getChat();
        return OpenAiChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        AiProperties.Chat chat = properties.getChat();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .build();
    }

    /**
     * 多轮对话记忆：每个会话一个滑动窗口，存在 JVM 内存里。
     *
     * <p>不指定 {@code chatMemoryStore(...)} 时，窗口默认落在内存——服务重启即失忆；</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getMemory().getMaxMessages())
                .build();
    }

    /**
     * "伴读爸爸妈妈"：AiServices 动态代理，人设由 system prompt 定义，记忆按会话隔离。
     */
    @Bean
    public Companion companion(ChatModel chatModel,
                           StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider) {
        String systemPrompt = readSystemPrompt();
        return AiServices.builder(Companion.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
    }

    /**
     * 人设 system prompt 独立成文件（prompts/system-prompt.txt）。
     * 这里在启动装配时读一次，之后所有会话共用同一份人设。
     */
    private String readSystemPrompt() {
        try (var in = new ClassPathResource("prompts/system-prompt.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取人设 prompt 失败：prompts/system-prompt.txt", e);
        }
    }
}
