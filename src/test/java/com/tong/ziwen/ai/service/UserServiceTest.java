package com.tong.ziwen.ai.service;

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

import com.tong.ziwen.auth.service.AccountService;
import com.tong.ziwen.common.exception.BizException;
import com.tong.ziwen.ai.dto.ChatWindow;
import com.tong.ziwen.ai.dto.ChildProfile;

/**
 * 娃档案与聊天窗口的业务层测试：跑在内存 SQLite 上，执行仓库里同一份 schema.sql。
 * 家长账号的注册登录测试在 auth 包的 AccountServiceTest。
 *
 * <p>本类的主题就是"谁和谁永不相见"——sessionId 的隔离与归属校验全被覆盖：
 * 跨家庭串号在 sessionIdOf 一处拦死，同娃两个窗口、家庭内两个娃
 * 键互不相同。</p>
 */
class UserServiceTest {

    private AccountService accountService;
    private UserService userService;

    @BeforeEach
    void setUpInMemorySqlite() throws SQLException {
        // SQLite 的内存库按连接隔离：SingleConnectionDataSource 全程复用同一连接，
        // 建的表才不会在下一 getConnection 时消失
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        accountService = new AccountService(jdbcTemplate);
        userService = new UserService(jdbcTemplate);
    }

    @Test
    void sessionIdScopesWindowsApart() {
        // 验收例的一半：同一个娃开两个窗口，两窗口的 sessionId 互不相同——隔离就是键不同，别无魔法
        long parentId = accountService.register("dad", "secret-123").id();
        ChildProfile child = userService.createChild(parentId, "老大");
        ChatWindow first = userService.createWindow(parentId, child.id());
        ChatWindow second = userService.createWindow(parentId, child.id());

        assertThat(userService.sessionIdOf(parentId, first.id()))
                .isEqualTo(parentId + ":" + child.id() + ":" + first.id())
                .isNotEqualTo(userService.sessionIdOf(parentId, second.id()));
    }

    @Test
    void duplicateNicknameRejectedWithinFamily() {
        // 同名防重兜底与 username 同一思路；这条断言同时守着 schema 里的
        // idx_child_profile_parent_nickname 唯一索引：索引丢了，重名建娃会直接成功而非报错
        long parentId = accountService.register("dad", "secret-123").id();
        userService.createChild(parentId, "老大");

        assertThatThrownBy(() -> userService.createChild(parentId, "老大"))
                .isInstanceOf(BizException.class)
                .hasMessage("已经有叫这个昵称的娃了");
    }

    @Test
    void sameNicknameAllowedAcrossFamilies() {
        // 唯一范围是 (parent_id, nickname)：两家的"老大"互不干涉
        long parentOne = accountService.register("dad", "secret-123").id();
        long parentTwo = accountService.register("mom", "secret-456").id();
        userService.createChild(parentOne, "老大");

        assertThat(userService.createChild(parentTwo, "老大").id()).isPositive();
    }

    @Test
    void sessionIdRejectedForOthersWindow() {
        // 验收例的另一半：家长 2 拿着家长 1 的窗口 id 来要 sessionId——拒绝。
        // 家长 id 来自登录态伪造不了，但请求参数可以是任何数，窗口→娃→家长
        // 三层归属校验就是给这道口子上锁
        long parentOne = accountService.register("dad", "secret-123").id();
        long parentTwo = accountService.register("mom", "secret-456").id();
        ChildProfile child = userService.createChild(parentOne, "老大");
        ChatWindow window = userService.createWindow(parentOne, child.id());

        assertThatThrownBy(() -> userService.sessionIdOf(parentTwo, window.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个窗口");
    }

    @Test
    void listChildrenScopesToParent() {
        long parentOne = accountService.register("dad", "secret-123").id();
        long parentTwo = accountService.register("mom", "secret-456").id();
        userService.createChild(parentOne, "老大");
        userService.createChild(parentTwo, "小宝");

        assertThat(userService.listChildren(parentOne))
                .extracting(ChildProfile::nickname)
                .containsExactly("老大");
    }

    @Test
    void windowLifecycleUnderOwner() {
        long parentOne = accountService.register("dad", "secret-123").id();
        long parentTwo = accountService.register("mom", "secret-456").id();
        ChildProfile child = userService.createChild(parentOne, "老大");

        // 别家的娃名下开不了窗口
        assertThatThrownBy(() -> userService.createWindow(parentTwo, child.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个娃");

        ChatWindow window = userService.createWindow(parentOne, child.id());
        assertThat(userService.listWindows(parentOne, child.id()))
                .extracting(ChatWindow::title)
                .containsExactly("新聊天");

        // 别家也删不了这个窗口：娃归属先拦下，同句"没有这个娃"
        assertThatThrownBy(() -> userService.deleteWindow(parentTwo, child.id(), window.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个娃");

        userService.deleteWindow(parentOne, child.id(), window.id());
        assertThat(userService.listWindows(parentOne, child.id())).isEmpty();
    }

    @Test
    void firstQuestionBecomesWindowTitleOnlyOnce() {
        // 首问自动命名：默认标题换成问题开头；之后的问答不再覆盖；超长截断加省略号
        long parentId = accountService.register("dad", "secret-123").id();
        ChildProfile child = userService.createChild(parentId, "老大");
        ChatWindow window = userService.createWindow(parentId, child.id());

        userService.retitleIfDefault(parentId, window.id(), "灌字是哪课学的呀");
        userService.retitleIfDefault(parentId, window.id(), "第二句不该覆盖标题");
        assertThat(userService.listWindows(parentId, child.id()).get(0).title())
                .isEqualTo("灌字是哪课学的呀");

        ChatWindow longWindow = userService.createWindow(parentId, child.id());
        userService.retitleIfDefault(parentId, longWindow.id(), "这是一句超过十六个字符的特别特别长的问题");
        assertThat(userService.listWindows(parentId, child.id()).get(1).title())
                .isEqualTo("这是一句超过十六个字符的特别特别…");
    }

    @Test
    void deleteChildCascadesWindowSessionIds() {
        // 删娃前先收 sessionId 清单：娃行、窗口行删掉之后归属链回溯不了，键必须提前拼好
        long parentId = accountService.register("dad", "secret-123").id();
        ChildProfile child = userService.createChild(parentId, "老大");
        ChatWindow first = userService.createWindow(parentId, child.id());
        ChatWindow second = userService.createWindow(parentId, child.id());

        assertThat(userService.sessionIdsOfChild(parentId, child.id()))
                .containsExactly(
                        parentId + ":" + child.id() + ":" + first.id(),
                        parentId + ":" + child.id() + ":" + second.id());

        userService.deleteChild(parentId, child.id());
        assertThatThrownBy(() -> userService.sessionIdsOfChild(parentId, child.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个娃");
    }

    @Test
    void deleteChildOnlyByOwner() {
        long parentOne = accountService.register("dad", "secret-123").id();
        long parentTwo = accountService.register("mom", "secret-456").id();
        ChildProfile child = userService.createChild(parentOne, "老大");
        ChatWindow window = userService.createWindow(parentOne, child.id());

        // 别家家长删不动，也不报具体原因——同"没有这个娃"
        assertThatThrownBy(() -> userService.deleteChild(parentTwo, child.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个娃");
        assertThat(userService.listChildren(parentOne)).hasSize(1);

        // 本人可删，删完窗口行也没了，sessionId 也拼不出来了
        userService.deleteChild(parentOne, child.id());
        assertThat(userService.listChildren(parentOne)).isEmpty();
        assertThatThrownBy(() -> userService.listWindows(parentOne, child.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个娃");
        assertThatThrownBy(() -> userService.sessionIdOf(parentOne, window.id()))
                .isInstanceOf(BizException.class)
                .hasMessage("没有这个窗口");
    }

    @Test
    void childIdNeverReusedAfterDelete() {
        // AUTOINCREMENT 的意义：删娃重建后 id 只增不回头，前端残留的旧 childId
        // 永远接不上新娃——记忆不会张冠李戴
        long parentId = accountService.register("dad", "secret-123").id();
        ChildProfile first = userService.createChild(parentId, "老大");

        userService.deleteChild(parentId, first.id());
        ChildProfile second = userService.createChild(parentId, "小宝");

        assertThat(second.id()).isGreaterThan(first.id());
    }
}