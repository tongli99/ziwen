package com.tong.ziwen.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试：只验证所有 Bean（AiConfig / AiProperties / Controller…）能装配成功。
 *
 * <p>不依赖真实模型：模型构建不发起网络请求，
 * 因此这里用一个占位 key 即可，纯验证装配与配置校验。</p>
 */
@SpringBootTest(properties = {
        "ai.chat.base-url=http://localhost:9999",
        "ai.chat.api-key=test-key",
        "ai.chat.model-name=test-model"
})
class ZiwenApplicationTests {

    @Test
    void contextLoads() {
    }
}
