package com.tong.ziwen.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.tong.ziwen.auth.model.ParentAccount;
import com.tong.ziwen.common.exception.BizException;

/**
 * 家长账号的业务层测试：跑在内存 SQLite 上，执行仓库里同一份 schema.sql。
 *
 * <p>主题是注册与登录的正反两面：密码哈希落库后能原样登录回来；
 * 重名、错密码、不存在的用户名各有各的拒绝文案。</p>
 */
class AccountServiceTest {

    private AccountService accountService;

    @BeforeEach
    void setUpInMemorySqlite() throws SQLException {
        // SQLite 的内存库按连接隔离：SingleConnectionDataSource 全程复用同一连接，
        // 建的表才不会在下一 getConnection 时消失
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        accountService = new AccountService(
                new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
    }

    @Test
    void registerThenLoginRoundTrips() {
        // 注册后用同一密码能登录回来，拿到的家长 id 与注册时一致
        ParentAccount account = accountService.register("dad", "secret-123");

        assertThat(account.id()).isPositive();
        assertThat(accountService.login("dad", "secret-123")).isEqualTo(account.id());
    }

    @Test
    void duplicateUsernameRejected() {
        // 重名走 UNIQUE 约束兜底。这条断言同时守着 classpath 根的 sql-error-codes.xml：
        // 那份"错误码 19 → 重复键"的映射若失效，异常会掉进 UncategorizedSQLException，
        // 这里立刻红，不会等到线上才发现重名注册报的是"系统繁忙"
        accountService.register("dad", "secret-123");

        assertThatThrownBy(() -> accountService.register("dad", "other-456"))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名已被注册");
    }

    @Test
    void wrongPasswordRejected() {
        accountService.register("dad", "secret-123");

        assertThatThrownBy(() -> accountService.login("dad", "wrong"))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码不对");
    }

    @Test
    void unknownUsernameRejectedWithSameMessageAsWrongPassword() {
        // 防枚举：用户名不存在与密码错误必须同文案——分开说等于告诉探测者哪些用户名注册过
        assertThatThrownBy(() -> accountService.login("nobody", "whatever"))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码不对");
    }
}