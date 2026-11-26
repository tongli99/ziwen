-- 生字表（v0.4 起）：字 → 课 → 页 的事实性数据，精确匹配查询，不走向量检索。
-- 建表幂等：IF NOT EXISTS，重复启动不重建已录入的数据
CREATE TABLE IF NOT EXISTS hanzi_entry (
    -- 版次：课本印次/年份。页码随版次漂移（换版可能挪页），课序跨版稳定，
    -- 所以主键锚在 (edition, lesson_no, hanzi)，页码只是普通列
    edition      TEXT    NOT NULL,
    -- 课序：全册从 1 连续编号。检索与排序的主锚点
    lesson_no    INTEGER NOT NULL,
    hanzi        TEXT    NOT NULL,
    -- 拼音带声调；多音字把几个读法一并列出（斜线分隔，如 "wéi/wèi"）
    pinyin       TEXT    NOT NULL,
    stroke_count INTEGER NOT NULL,
    radical      TEXT    NOT NULL,
    -- 页码：本版课本里该课生字表所在页，只对本版负责
    page_no      INTEGER NOT NULL,
    PRIMARY KEY (edition, lesson_no, hanzi)
);

-- 主键 (edition, lesson_no, hanzi) 是按三列顺序排好的联合索引，查询条件必须
-- 从最左列 edition 开始才能用上；查询层的两条语句分别只按 hanzi、只按
-- lesson_no 过滤，主键都帮不上，只能全表扫。所以给 hanzi 单独建索引——
-- 查字→课是这张表最高频的问法；lesson_no 不建：表只有几百行，全表扫也是微秒级
CREATE INDEX IF NOT EXISTS idx_hanzi_entry_hanzi ON hanzi_entry (hanzi);

-- 家长账号（v0.5 起）：注册主体是家长。密码只存 BCrypt 哈希（单向、自带盐），
-- 明文在请求处理完就没了，库里这列无法还原出密码
CREATE TABLE IF NOT EXISTS parent_account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 用户名唯一：重名注册靠它兜底（并发窗口内"先查后插"不可靠）
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 娃档案（家长的子账号）：只有昵称
CREATE TABLE IF NOT EXISTS child_profile (
    -- AUTOINCREMENT：SQLite 默认会复用被删行的 rowid；加上它 id 只增不复用，
    -- 删掉的娃 id 永远不会再指向新娃，前端残留的旧 childId 接不上别人的记忆
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id  INTEGER NOT NULL,
    nickname   TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (parent_id) REFERENCES parent_account(id)
);

-- 同一家长名下昵称唯一：同名娃在前端列表里分不清，防重兜底与 username 的 UNIQUE
-- 同一思路。不写进建表语句而单独建索引：CREATE TABLE IF NOT EXISTS 对已建过表的
-- 老库不会补约束，CREATE UNIQUE INDEX IF NOT EXISTS 新老库都能生效。
-- 范围是 (parent_id, nickname) 而非全局 nickname：两家的"老大"互不干涉
CREATE UNIQUE INDEX IF NOT EXISTS idx_child_profile_parent_nickname
    ON child_profile (parent_id, nickname);

-- 聊天窗口（家长的孙账号，一个娃可开多个）：每个窗口一条独立记忆，
-- 记忆键细化到 parentId:childId:windowId。
-- 窗口与娃档案同思路：也是服务端实体，归属链 窗口→娃→家长 在服务端校验
CREATE TABLE IF NOT EXISTS chat_window (
    -- AUTOINCREMENT：同娃档案的理由——删掉的窗口 id 不复用，前端残留的
    -- 旧 windowId 接不上别人的记忆
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    child_id   INTEGER NOT NULL,
    -- 新窗口的默认标题；首轮问答后由服务端换成问题开头，同娃多窗口才分得清
    title      TEXT NOT NULL DEFAULT '新聊天',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (child_id) REFERENCES child_profile(id)
);

CREATE INDEX IF NOT EXISTS idx_chat_window_child ON chat_window (child_id);
