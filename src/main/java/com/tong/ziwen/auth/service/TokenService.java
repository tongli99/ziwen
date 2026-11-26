package com.tong.ziwen.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.tong.ziwen.auth.AuthProperties;
import lombok.RequiredArgsConstructor;

/**
 * 登录 token 的签发与校验：token 是一串密码学随机数，真正的映射关系
 * "token → 家长 id" 存在 Redis 里。
 *
 * <p><b>为什么不把家长 id 编进 token 再签名防伪</b>：那种方案省一次 Redis 查询，
 * 但注销、踢下线都做不了（签出去就是有效的）。无状态换不来控制力，本处要的
 * 恰恰是"revoke 即失效"，跟对话记忆同一个思路：会话状态放 Redis，滑动过期。</p>
 *
 * <p>token 本身不携任何信息，泄漏了也只是"某次登录的通行证"，到期自动作废。</p>
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    /** token 在 Redis 的 key 前缀，与对话记忆的前缀平级，方便按业务清点。 */
    private static final String KEY_PREFIX = "ziwen:auth:token:";

    /** 密码学随机源：SecureRandom 与 Math.random / Random 的区别是输出不可预测。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 字节 = 256 位熵，十六进制展开 64 个字符，碰撞和穷举都不现实。 */
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties properties;

    /**
     * 给家长签发一个新 token 并写入 Redis。
     *
     * <p>每次登录都签新 token：同一家长多设备可并存（各是各的 key），
     * 互不挤占；要"只允许一处登录"再加"删该家长全部 token"的逻辑即可，此处从简。</p>
     */
    public String issueToken(long parentId) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(randomBytes);
        String token = HexFormat.of().formatHex(randomBytes);
        redisTemplate.opsForValue().set(keyOf(token), Long.toString(parentId), tokenTtl());
        return token;
    }

    /**
     * 校验 token 并取回家长 id；无效（不存在或已注销）返回 null。
     *
     * <p>命中即续期（滑动过期）：只要家长还常来，就不用重新登录；
     * 连续 {@code tokenTtl}（默认 7 天）不打开字问，登录态自动失效。</p>
     */
    public Long parentIdOf(String token) {
        String parentId = redisTemplate.opsForValue().get(keyOf(token));
        if (parentId == null) {
            return null;
        }
        redisTemplate.expire(keyOf(token), tokenTtl());
        return Long.valueOf(parentId);
    }

    /** 注销：删除映射，token 立即失效（不等到自然过期）。 */
    public void revoke(String token) {
        redisTemplate.delete(keyOf(token));
    }

    private Duration tokenTtl() {
        return properties.getTokenTtl();
    }

    private String keyOf(String token) {
        return KEY_PREFIX + token;
    }
}
