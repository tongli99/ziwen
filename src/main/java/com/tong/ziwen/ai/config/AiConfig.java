package com.tong.ziwen.ai.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.tong.ziwen.ai.service.Companion;
import com.tong.ziwen.ai.store.RedisChatMemoryStore;
import com.tong.ziwen.ai.tool.HanziTool;
import com.tong.ziwen.ai.tool.PoemSearchTool;
import com.tong.ziwen.ai.tool.ToolCallLimiter;
import com.tong.ziwen.ai.tool.ToolGuardrailExecutor;
import com.tong.ziwen.ai.tool.ToolStatusHub;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom;

/**
 * LangChain4j Bean 装配。
 *
 * <p>对话模型走 OpenAI 兼容协议（OpenAiChatModel / OpenAiStreamingChatModel），
 * 通过 base-url 指向 DeepSeek 等任意兼容提供方。流式与同步使用同一套参数。</p>
 *
 * <p>本类认领 {@link AiProperties} 的注册：谁消费这份配置谁注册，主类不集中代办。</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AiProperties.class)
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
     * 多轮对话记忆：每个会话一个滑动窗口，窗口内容整体持久化到 Redis。
     *
     * <p>窗口策略与存储在本处组合：{@code MessageWindowChatMemory} 负责裁剪，
     * {@code RedisChatMemoryStore} 负责持久化。服务重启后同一 {@code memoryId}
     * 仍能读回历史，重启不丢失记忆即依赖这一处 {@code chatMemoryStore(...)}。</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getMemory().getMaxMessages())
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * “伴读爸爸妈妈”人设：AiServices 动态代理，人设由 system prompt 定义，记忆按会话隔离。
     *
     * <p>v0.3 起编排方式变更：v0.2 的 {@code retrievalAugmentor}（每轮固定先检索）
     * 改为 {@code tools} 挂载 {@link PoemSearchTool}，是否检索由模型在对话中自行决定。
     * ChatController 和 Companion 接口因此无需改动。</p>
     *
     * <p>v0.3.2 起工具经 {@link ToolGuardrailExecutor} 包装后再挂载（调用限次、状态推送），
     * {@code maxToolCallingRoundTrips} 同步收紧为配置值，作为模型无视劝停文本时的最后防线。</p>
     *
     * <p>v0.4 起 {@link HanziTool} 的两个生字表查询加入挂载：古诗语义检索与
     * 生字结构化查询两套检索并存，路由由模型按各工具的说明书自行决定。</p>
     */
    @Bean
    public Companion companion(ChatModel chatModel,
                           StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           PoemSearchTool poemSearchTool,
                           HanziTool hanziTool,
                           ToolCallLimiter toolCallLimiter,
                           ToolStatusHub toolStatusHub) {
        String systemPrompt = readSystemPrompt();
        return AiServices.builder(Companion.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .tools(toolsWithGuardrails(poemSearchTool, hanziTool, toolCallLimiter, toolStatusHub))
                .maxToolCallingRoundTrips(properties.getTool().getMaxRoundTrips())
                .build();
    }

    /**
     * 全部工具包上护栏后交给 AiServices（v0.4 起共三个：古诗语义检索、
     * 查字→课、按课列字）。工具描述仍从 {@code @Tool} 注解反射生成
     * （{@code @ToolMemoryId} 参数不进描述），执行链从直接调用方法变为“护栏 → 方法”。
     * 方法签名变更时此处启动即抛出异常，异常消息带有定位提示。
     */
    private List<AiServiceTool> toolsWithGuardrails(PoemSearchTool poemSearchTool,
                                                    HanziTool hanziTool,
                                                    ToolCallLimiter toolCallLimiter,
                                                    ToolStatusHub toolStatusHub) {
        return List.of(
                guardTool(poemSearchTool, "searchPoems",
                        new Class<?>[] {String.class, String.class},
                        "字问先生正在翻古诗书…", toolCallLimiter, toolStatusHub),
                guardTool(hanziTool, "findLessonsByHanzi",
                        new Class<?>[] {String.class, String.class},
                        "字问先生正在翻生字表…", toolCallLimiter, toolStatusHub),
                guardTool(hanziTool, "listCharsByLesson",
                        new Class<?>[] {int.class, String.class},
                        "字问先生正在整理预习字表…", toolCallLimiter, toolStatusHub));
    }

    /**
     * 单个 {@code @Tool} 方法的护栏装配：反射拿到 Method 后手工复刻框架的注册流程——
     * 说明书交给 {@code toolSpecificationFrom} 从注解生成，执行器由
     * {@code DefaultToolExecutor.builder} 反射调用方法，再包一层护栏。护栏不是
     * Spring bean（状态文案因工具而异，每个工具现场 new 一个），不能换成注入单例。
     */
    private AiServiceTool guardTool(Object toolBean, String methodName, Class<?>[] parameterTypes,
                                    String workingStatusText, ToolCallLimiter toolCallLimiter,
                                    ToolStatusHub toolStatusHub) {
        try {
            Method toolMethod = toolBean.getClass().getMethod(methodName, parameterTypes);
            // 按照框架 .tools(对象) 的内部注册方式创建方法执行器，异常处理开关与框架默认保持一致
            ToolExecutor plainExecutor = DefaultToolExecutor.builder()
                    .object(toolBean)
                    .originalMethod(toolMethod)
                    .methodToInvoke(toolMethod)
                    .wrapToolArgumentsExceptions(true)
                    .propagateToolExecutionExceptions(true)
                    .build();
            return AiServiceTool.builder()
                    .toolSpecification(toolSpecificationFrom(toolMethod))
                    .toolExecutor(new ToolGuardrailExecutor(
                            plainExecutor, workingStatusText, toolCallLimiter, toolStatusHub))
                    .build();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("装配工具失败：%s 的 %s 方法签名变了吗？记得同步这里"
                    .formatted(toolBean.getClass().getSimpleName(), methodName), e);
        }
    }

    /**
     * 人设 system prompt 独立存放于 prompts/system-prompt.txt。
     * 启动装配时读取一次，之后所有会话共用同一份。
     */
    private String readSystemPrompt() {
        try (var in = new ClassPathResource("prompts/system-prompt.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取人设 prompt 失败：prompts/system-prompt.txt", e);
        }
    }
}
