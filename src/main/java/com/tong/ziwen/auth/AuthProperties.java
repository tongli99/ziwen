package com.tong.ziwen.auth;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import lombok.Data;

/**
 * 登录 token 配置（v0.5 起），绑定 application.yml 中 {@code ai.auth} 前缀——
 * 前缀仍挂在 ai 之下，配置文件不用跟着包结构调整。
 *
 * <p>token 存 Redis、滑动过期：校验通过即续期，连续 tokenTtl 不访问，
 * 登录态自然失效，家长需要重新登录。</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.auth")
public class AuthProperties {

    /** token 存活时间。 */
    @DurationUnit(ChronoUnit.DAYS)
    private Duration tokenTtl = Duration.ofDays(7);
}
