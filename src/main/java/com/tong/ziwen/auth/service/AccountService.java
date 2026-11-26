package com.tong.ziwen.auth.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.tong.ziwen.auth.model.ParentAccount;
import com.tong.ziwen.common.exception.BizException;
import lombok.RequiredArgsConstructor;

/**
 * 家长账号的业务层：注册与登录。与娃档案、聊天窗口（UserService）分开——
 * 账号是"谁在用"，娃是"给谁用"，两件事各自的表、各自的校验。
 *
 * <p>注册主体是家长、娃只是家长名下的代号（昵称一个字段）。
 * parent_account 表在 schema.sql，建表幂等。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String FIND_PARENT_BY_USERNAME =
            "SELECT id, username, password_hash FROM parent_account WHERE username = ?";
    private static final String INSERT_PARENT =
            "INSERT INTO parent_account(username, password_hash) VALUES(?, ?)";

    /**
     * BCrypt 哈希器：无状态、线程安全，直接持有即可，没必要单独注册成 Bean。
     * 只引入 spring-security-crypto 这一个密码学模块，不引 Spring Security 全套。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 家长注册：密码哈希落库，返回新账号。
     *
     * <p>重名不靠先查后插判断（两步之间有并发窗口），直接依赖 username 上的
     * UNIQUE 约束兜底。撞约束时 Spring 把 SQLException 翻译成
     * {@link DuplicateKeyException}——前提是错误码表里查得到 SQLite：
     * Spring 自带的表没收录它，靠 classpath 根的 sql-error-codes.xml 补了
     * "错误码 19 → 重复键"的映射；没有那份文件，异常会掉进
     * UncategorizedSQLException（"未归类"大筐），这里的 catch 接不住。</p>
     *
     * <p>取新 id 走 JDBC 的 {@code getGeneratedKeys}（{@link GeneratedKeyHolder}），
     * 不走 {@code SELECT last_insert_rowid()}：后者查的是"当前连接"的最后插入，
     * 连接池里多请求并发时两家的 INSERT 互相串 id；前者跟着这条语句走，不串。</p>
     */
    public ParentAccount register(String username, String password) {
        String passwordHash = passwordEncoder.encode(password);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(INSERT_PARENT,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, username);
                statement.setString(2, passwordHash);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw new BizException("用户名已被注册");
        }
        return new ParentAccount(keyHolder.getKey().longValue(), username, passwordHash);
    }

    /**
     * 家长登录：校验通过返回家长 id。
     *
     * <p>错误文案不区分"用户名不存在"和"密码不对"——分开说等于帮探测者
     * 免费枚举出哪些用户名注册过（撞库的前置情报）。</p>
     */
    public long login(String username, String password) {
        return findParentByUsername(username)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .map(ParentAccount::id)
                .orElseThrow(() -> new BizException("用户名或密码不对"));
    }

    private Optional<ParentAccount> findParentByUsername(String username) {
        List<ParentAccount> accounts = jdbcTemplate.query(FIND_PARENT_BY_USERNAME,
                (resultSet, rowNumber) -> new ParentAccount(
                        resultSet.getLong("id"),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash")),
                username);
        return accounts.stream().findFirst();
    }
}