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
