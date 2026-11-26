package com.tong.ziwen.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.tong.ziwen.auth.AuthProperties;

/**
 * 登录 token 的签发与校验测试：Redis 操作全部 mock，不依赖真实 Redis——
 * 本类验证的是"写没写、续没续、删没删"的调用契约，Redis 本身的正确性不在测试范围。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        // 手动构造而不是 @InjectMocks：构造器里 AuthProperties 不是 mock，
        // 注入会得到 null，tokenTtl() 取值时就 NPE 了
        tokenService = new TokenService(redisTemplate, new AuthProperties());
    }

    @Test
    void issueTokenStoresParentIdUnderTokenKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration ttl = new AuthProperties().getTokenTtl();

        String token = tokenService.issueToken(42L);

        // token 是 32 字节的十六进制（64 字符），映射关系带 TTL 写入 Redis
        assertThat(token).hasSize(64);
        verify(valueOperations).set(startsWith("ziwen:auth:token:"), eq("42"), eq(ttl));
    }

    @Test
    void issueTokenIsUnpredictablePerCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 两次签发不可能是同一个 token：SecureRandom 的输出无规律可循
        String first = tokenService.issueToken(42L);
        String second = tokenService.issueToken(42L);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void parentIdOfRenewsTtlOnHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("42");
        Duration ttl = new AuthProperties().getTokenTtl();

        String token = tokenService.issueToken(42L);
        Long parentId = tokenService.parentIdOf(token);

        assertThat(parentId).isEqualTo(42L);
        // 命中即续期：家长还常来就不用重新登录
        verify(redisTemplate).expire(startsWith("ziwen:auth:token:"), eq(ttl));
    }

    @Test
    void parentIdOfUnknownTokenReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(tokenService.parentIdOf("no-such-token")).isNull();
        // 不存在的 token 不续期，也不给探测者任何额外反馈
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void revokeDeletesTokenKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String token = tokenService.issueToken(42L);

        tokenService.revoke(token);

        verify(redisTemplate).delete(startsWith("ziwen:auth:token:"));
    }
}
