package com.tong.ziwen.ai.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tong.ziwen.common.exception.BizException;
import com.tong.ziwen.ai.dto.ChatWindow;
import com.tong.ziwen.ai.dto.ChildProfile;
import lombok.RequiredArgsConstructor;

/**
 * 娃档案与聊天窗口的业务层：SQLite 白名单模板查询，风格与 {@link HanziService} 一致。
 * 家长账号（注册/登录）在 auth 包的 AccountService，两件事各自的表、各自的校验。
 *
 * <p>娃只是家长名下的代号（昵称一个字段），是合规设计的落点：
 * 儿童个人信息（姓名、学校、生日、学籍）在本层没有入口，无需谈"怎么保护"。
 * 娃、窗口两张表在 schema.sql，建表幂等。</p>
 *
 * <p><b>sessionId 即命名空间</b>：{@link #sessionIdOf} 是隔离的核心——家长 id 来自
 * 登录态、窗口经 窗口→娃→家长 归属链校验后拼成 {@code parentId:childId:windowId}，
 * 两个窗口、两个娃、两个家庭、甚至"删娃重建/删窗口重建"都不会共用一个键。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String INSERT_CHILD =
            "INSERT INTO child_profile(parent_id, nickname) VALUES(?, ?)";
    private static final String FIND_CHILDREN_BY_PARENT =
            "SELECT id, nickname FROM child_profile WHERE parent_id = ? ORDER BY id";
    private static final String FIND_CHILD_BY_ID =
            "SELECT id, parent_id, nickname FROM child_profile WHERE id = ?";
    private static final String DELETE_CHILD =
            "DELETE FROM child_profile WHERE id = ? AND parent_id = ?";
    private static final String INSERT_WINDOW =
            "INSERT INTO chat_window(child_id, title) VALUES(?, ?)";
    private static final String FIND_WINDOWS_BY_CHILD =
            "SELECT id, title FROM chat_window WHERE child_id = ? ORDER BY id";
    private static final String FIND_WINDOW_IDS_BY_CHILD =
            "SELECT id FROM chat_window WHERE child_id = ? ORDER BY id";
    private static final String FIND_WINDOW_BY_ID =
            "SELECT id, child_id, title FROM chat_window WHERE id = ?";
    private static final String DELETE_WINDOW_BY_CHILD =
            "DELETE FROM chat_window WHERE id = ? AND child_id = ?";
    private static final String DELETE_WINDOWS_BY_CHILD =
            "DELETE FROM chat_window WHERE child_id = ?";
    private static final String UPDATE_WINDOW_TITLE =
            "UPDATE chat_window SET title = ? WHERE id = ?";

    /** 新窗口的默认标题；首轮问答后由 {@link #retitleIfDefault} 换成问题开头。 */
    private static final String DEFAULT_WINDOW_TITLE = "新聊天";
    /** 自动标题的截断长度：侧栏宽度有限，问题开头取前 16 字够了。 */
    private static final int TITLE_MAX_LENGTH = 16;

    private final JdbcTemplate jdbcTemplate;
    
    /**
     * 给家长新建一个娃档案。昵称只做 trim，"老大""小宝"是代号，没有更多语义可校验；
     * 同名防重靠唯一索引兜底，不做先查后插。
     *
     * <p>唯一范围是 (parent_id, nickname)：一家只能有一个"老大"，两家的"老大"
     * 互不干涉——撞索引时同样被翻译成 {@link DuplicateKeyException}，文案换成
     * 娃的语境。</p>
     */
    public ChildProfile createChild(long parentId, String nickname) {
        String cleanNickname = nickname.trim();
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(INSERT_CHILD,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, parentId);
                statement.setString(2, cleanNickname);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw new BizException("已经有叫这个昵称的娃了");
        }
        return new ChildProfile(keyHolder.getKey().longValue(), cleanNickname);
    }

    /** 列出家长名下全部娃，按创建顺序：老大在前是自然预期。 */
    public List<ChildProfile> listChildren(long parentId) {
        return jdbcTemplate.query(FIND_CHILDREN_BY_PARENT,
                (resultSet, rowNumber) -> new ChildProfile(
                        resultSet.getLong("id"), resultSet.getString("nickname")),
                parentId);
    }

    /** 给娃开一个新聊天窗口，标题先落默认值。归属校验先行：别家的娃名下开不了窗口。 */
    public ChatWindow createWindow(long parentId, long childId) {
        requireOwnedChild(parentId, childId);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT_WINDOW,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, childId);
            statement.setString(2, DEFAULT_WINDOW_TITLE);
            return statement;
        }, keyHolder);
        return new ChatWindow(keyHolder.getKey().longValue(), DEFAULT_WINDOW_TITLE);
    }

    /** 列出娃名下全部窗口，按创建顺序。 */
    public List<ChatWindow> listWindows(long parentId, long childId) {
        requireOwnedChild(parentId, childId);
        return jdbcTemplate.query(FIND_WINDOWS_BY_CHILD,
                (resultSet, rowNumber) -> new ChatWindow(
                        resultSet.getLong("id"), resultSet.getString("title")),
                childId);
    }

    /**
     * 删娃前的记忆清单：娃名下每个窗口各自的 sessionId。必须在删娃（连带删窗口行）
     * 之前拼好——行删了就回溯不了归属链；控制器拿着这份清单再去清 Redis。
     */
    public List<String> sessionIdsOfChild(long parentId, long childId) {
        requireOwnedChild(parentId, childId);
        return listWindowIds(childId).stream()
                .map(windowId -> parentId + ":" + childId + ":" + windowId)
                .toList();
    }

    /**
     * 删除单个窗口。WHERE 带上娃 id：URL 里的 窗口→娃→家长 三层都对得上才动行
     * （同删娃的做法，动没动由影响行数说话）。
     */
    public void deleteWindow(long parentId, long childId, long windowId) {
        requireOwnedChild(parentId, childId);
        int deletedRows = jdbcTemplate.update(DELETE_WINDOW_BY_CHILD, windowId, childId);
        if (deletedRows == 0) {
            throw new BizException("没有这个窗口");
        }
    }

    /**
     * 首轮问答把默认标题换成问题开头：同娃多窗口全叫"新聊天"就分不清了。
     * 只换默认标题，之后的问答不再覆盖；超长问题截断加省略号。
     */
    public void retitleIfDefault(long parentId, long windowId, String question) {
        WindowRow window = requireOwnedWindow(parentId, windowId);
        if (!DEFAULT_WINDOW_TITLE.equals(window.title())) {
            return;
        }
        String trimmed = question.trim();
        String title = trimmed.length() <= TITLE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
        jdbcTemplate.update(UPDATE_WINDOW_TITLE, title, windowId);
    }

    /**
     * 校验窗口归属并返回 sessionId（{@code parentId:childId:windowId}）——隔离防线的后半段。
     *
     * <p>前半段（家长 id 出自登录态）保证了伪造不出别的家长 id，但没有拦住
     * "家长 A 拿着家长 B 的 windowId 调接口"。本方法把窗口查回来，沿
     * 窗口→娃→家长 核对归属，不是自家窗口一律拒绝——跨家庭串号比兄弟串号
     * 更严重，在这里拦死。</p>
     *
     * <p>返回的键就是 {@code @MemoryId} 的全部内容：记忆隔离不是玄学，
     * 是"一个 key 的命名空间"。</p>
     */
    public String sessionIdOf(long parentId, long windowId) {
        WindowRow window = requireOwnedWindow(parentId, windowId);
        return parentId + ":" + window.childId() + ":" + windowId;
    }

    /**
     * 删除娃档案，连带删其名下全部窗口行。WHERE 带上家长 id：归属校验和删除
     * 一趟完成——不是自家娃则一行都不动，动没动由影响行数说话，没删成就是
     * 业务异常。窗口行在娃行之后删：SQLite 默认不强制外键，顺序反了只会
     * 留下指向已删娃的脏引用，不报错但难查。
     *
     * <p>两条 DELETE 绑在一个事务里：窗口行删失败时娃行一并回滚，孤儿窗口行
     * 在数据库层就杜绝，而不是靠调用方事后对账补救。事务只管 SQLite——Redis
     * 侧的记忆清理在事务外的 controller 编排，取舍在那里交代（宁可记忆残留
     * 等 TTL，不可先删了记忆再删档失败）。</p>
     */
    @Transactional
    public void deleteChild(long parentId, long childId) {
        int deletedRows = jdbcTemplate.update(DELETE_CHILD, childId, parentId);
        if (deletedRows == 0) {
            throw new BizException("没有这个娃");
        }
        jdbcTemplate.update(DELETE_WINDOWS_BY_CHILD, childId);
    }

    private Optional<ChildRow> findChildRow(long childId) {
        List<ChildRow> rows = jdbcTemplate.query(FIND_CHILD_BY_ID, CHILD_ROW_MAPPER, childId);
        return rows.stream().findFirst();
    }

    private Optional<WindowRow> findWindowRow(long windowId) {
        List<WindowRow> rows = jdbcTemplate.query(FIND_WINDOW_BY_ID, WINDOW_ROW_MAPPER, windowId);
        return rows.stream().findFirst();
    }

    private List<Long> listWindowIds(long childId) {
        return jdbcTemplate.queryForList(FIND_WINDOW_IDS_BY_CHILD, Long.class, childId);
    }

    /** 娃归属校验：找不到与不属于当前家长都回同一句"没有这个娃"，不泄露娃的存在性。 */
    private void requireOwnedChild(long parentId, long childId) {
        ChildRow childRow = findChildRow(childId)
                .orElseThrow(() -> new BizException("没有这个娃"));
        if (childRow.parentId() != parentId) {
            throw new BizException("没有这个娃");
        }
    }

    /**
     * 窗口归属校验：沿 窗口→娃→家长 核对，任一环对不上都回同一句
     * "没有这个窗口"。返回窗口行，调用方（如换标题）还要用它的字段。
     */
    private WindowRow requireOwnedWindow(long parentId, long windowId) {
        WindowRow window = findWindowRow(windowId)
                .orElseThrow(() -> new BizException("没有这个窗口"));
        ChildRow childRow = findChildRow(window.childId())
                .orElseThrow(() -> new BizException("没有这个窗口"));
        if (childRow.parentId() != parentId) {
            throw new BizException("没有这个窗口");
        }
        return window;
    }

    /** 归属校验查询的行映射器：列名只在这里出现，改列名不动方法体。 */
    private static final RowMapper<ChildRow> CHILD_ROW_MAPPER = (resultSet, rowNumber) ->
            new ChildRow(resultSet.getLong("id"), resultSet.getLong("parent_id"),
                    resultSet.getString("nickname"));

    /** 娃行的内部载体：比对外暴露的 ChildProfile 多一个 parent_id，供归属校验用。 */
    private record ChildRow(long id, long parentId, String nickname) {
    }

    /** 窗口行映射器：同娃的做法，列名只在这里出现。 */
    private static final RowMapper<WindowRow> WINDOW_ROW_MAPPER = (resultSet, rowNumber) ->
            new WindowRow(resultSet.getLong("id"), resultSet.getLong("child_id"),
                    resultSet.getString("title"));

    /** 窗口行的内部载体：比对外暴露的 ChatWindow 多一个 child_id，供归属链校验用。 */
    private record WindowRow(long id, long childId, String title) {
    }
}
