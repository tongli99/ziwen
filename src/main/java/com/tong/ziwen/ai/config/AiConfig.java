package com.tong.ziwen.ai.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import com.tong.ziwen.ai.service.Companion;
import com.tong.ziwen.ai.service.HanziTutor;
import com.tong.ziwen.ai.service.PoemTutor;
import com.tong.ziwen.ai.service.RouterAgent;
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
 *
 * <p>v0.5.1 起装配的 AiServices 分身有四个：{@link RouterAgent} 分诊、
 * {@link PoemTutor} 与 {@link HanziTutor} 两个专才、{@link Companion} 全能兜底；
 * 先分诊再分发的编排逻辑在 ChatDispatcher，不在本类。</p>
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
                .listeners(new ChatModelLogListener())
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
                .listeners(new ChatModelLogListener())
                .build();
    }

    /**
     * 多轮对话记忆：每个会话一个滑动窗口，窗口内容整体持久化到 Redis。
     *
     * <p>窗口策略与存储在本处组合：{@code MessageWindowChatMemory} 负责裁剪，
     * {@code RedisChatMemoryStore} 负责持久化。服务重启后同一 {@code memoryId}
     * 仍能读回历史，重启不丢失记忆即依赖这一处 {@code chatMemoryStore(...)}。</p>
     *
     * <p>v0.5.1 起打开 {@code alwaysKeepSystemMessageFirst}：每轮调用框架都把当前分身的 system message
     * 写进记忆，分身一换人就换一条；框架默认把新的追加到列表末尾，发给模型时它会夹在历史中间。
     * 打开后 system message 始终在第一条，换分身只换内容、不挪位置。</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getMemory().getMaxMessages())
                .chatMemoryStore(chatMemoryStore)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    /**
     * 分诊台：每轮问答的第一跳，把问句归入 背诗 / 查字 / 闲聊 三类。
     *
     * <p>不带记忆、不带工具：分类只看当前这句话，用完即弃。模型与对话模型同一套地址、密钥、
     * 模型名，只有 temperature 单独配到 0（取值见 application.yml）——分诊是分类，同一句话每次
     * 应归同一类。判据全部写进 router-prompt.txt，装配时读一次。</p>
     */
    @Bean
    public RouterAgent routerAgent() {
        AiProperties.Chat chat = properties.getChat();
        ChatModel routerModel = OpenAiChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getRouterTemperature())
                .listeners(new ChatModelLogListener())
                .build();
        String systemPrompt = readPrompt("prompts/router-prompt.txt");
        return AiServices.builder(RouterAgent.class)
                .chatModel(routerModel)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
    }

    /**
     * 背诗专才：只挂古诗检索一个工具，人设只讲古诗（poem-tutor-prompt.txt）。
     * 记忆、护栏、流式的装配与全能伴读同一套，只有工具组和人设换成专才的。
     */
    @Bean
    public PoemTutor poemTutor(ChatModel chatModel,
                           StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           PoemSearchTool poemSearchTool,
                           ToolCallLimiter toolCallLimiter,
                           ToolStatusHub toolStatusHub) {
        String systemPrompt = readPrompt("prompts/poem-tutor-prompt.txt");
        return AiServices.builder(PoemTutor.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .tools(poemToolsWithGuardrail(poemSearchTool, toolCallLimiter, toolStatusHub))
                .maxToolCallingRoundTrips(properties.getTool().getMaxRoundTrips())
                .build();
    }

    /**
     * 查字专才：只挂生字表两个查询工具，人设只讲生字（hanzi-tutor-prompt.txt）。
     * 装配结构与背诗专才同构，差异只在工具组与人设。
     */
    @Bean
    public HanziTutor hanziTutor(ChatModel chatModel,
                           StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           HanziTool hanziTool,
                           ToolCallLimiter toolCallLimiter,
                           ToolStatusHub toolStatusHub) {
        String systemPrompt = readPrompt("prompts/hanzi-tutor-prompt.txt");
        return AiServices.builder(HanziTutor.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .tools(hanziToolsWithGuardrail(hanziTool, toolCallLimiter, toolStatusHub))
                .maxToolCallingRoundTrips(properties.getTool().getMaxRoundTrips())
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
     *
     * <p>v0.5.1 起它是分诊的兜底分身：背诗、查字各有专才，其余（含分不准的）都落到
     * 这里——全工具全人设，误分诊也接得住。</p>
     */
    @Bean
    public Companion companion(ChatModel chatModel,
                           StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           PoemSearchTool poemSearchTool,
                           HanziTool hanziTool,
                           ToolCallLimiter toolCallLimiter,
                           ToolStatusHub toolStatusHub) {
        String systemPrompt = readPrompt("prompts/system-prompt.txt");
        return AiServices.builder(Companion.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .tools(allToolsWithGuardrails(poemSearchTool, hanziTool, toolCallLimiter, toolStatusHub))
                .maxToolCallingRoundTrips(properties.getTool().getMaxRoundTrips())
                .build();
    }

    /**
     * 全部三个工具包上护栏后交给兜底伴读：古诗组与生字组各自装好再合并，
     * 两组同时就是背诗、查字两个专才的工具清单。工具描述仍从 {@code @Tool} 注解
     * 反射生成（{@code @ToolMemoryId} 参数不进描述），执行链从直接调用方法变为
     * “护栏 → 方法”。方法签名变更时此处启动即抛出异常，异常消息带有定位提示。
     */
    private List<AiServiceTool> allToolsWithGuardrails(PoemSearchTool poemSearchTool,
                                                    HanziTool hanziTool,
                                                    ToolCallLimiter toolCallLimiter,
                                                    ToolStatusHub toolStatusHub) {
        return Stream.concat(
                poemToolsWithGuardrail(poemSearchTool, toolCallLimiter, toolStatusHub).stream(),
                hanziToolsWithGuardrail(hanziTool, toolCallLimiter, toolStatusHub).stream())
                .toList();
    }

    /** 背诗专才的工具组：只有古诗语义检索一个。 */
    private List<AiServiceTool> poemToolsWithGuardrail(PoemSearchTool poemSearchTool,
                                                   ToolCallLimiter toolCallLimiter,
                                                   ToolStatusHub toolStatusHub) {
        return List.of(
                guardTool(poemSearchTool, "searchPoems",
                        new Class<?>[] {String.class, String.class},
                        "字问先生正在翻古诗书…", toolCallLimiter, toolStatusHub));
    }

    /** 查字专才的工具组：查字→课、按课列字两个。 */
    private List<AiServiceTool> hanziToolsWithGuardrail(HanziTool hanziTool,
                                                    ToolCallLimiter toolCallLimiter,
                                                    ToolStatusHub toolStatusHub) {
        return List.of(
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
     * 读取 classpath 下 prompts/ 目录里的一份说明书。调用方在装配时读一次存成局部变量，
     * 之后所有轮次共用同一份；文件缺失启动即抛出，不会拖到第一次对话。
     * 路径作参数：分身各有各的人设文件，不再只有伴读一份。
     */
    private String readPrompt(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 失败：" + path, e);
        }
    }
}
