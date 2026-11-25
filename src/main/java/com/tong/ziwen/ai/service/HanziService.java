package com.tong.ziwen.ai.service;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.tong.ziwen.ai.dto.HanziEntry;
import lombok.RequiredArgsConstructor;

/**
 * 生字表查询：只读白名单模板查询（v0.4 起）。
 *
 * <p>SQL 全部预定义在本类里，占位符传参，模型永远不接触 SQL——
 * 它只负责从孩子的话里抽出"哪个字/哪一课"。这是与古诗 RAG 并存的另一条检索路：
 * 字→课是精确的键值事实，同音字、形近字经不起向量"近似"，必须精确匹配。</p>
 */
@Service
@RequiredArgsConstructor
public class HanziService {

    /** 列清单固定，行映射按列名取值。 */
    private static final String SELECT = """
            SELECT hanzi, pinyin, stroke_count, radical, lesson_no, page_no, edition
            FROM hanzi_entry""";

    private static final RowMapper<HanziEntry> ROW_MAPPER = (resultSet, rowNumber) -> new HanziEntry(
            resultSet.getString("hanzi"),
            resultSet.getString("pinyin"),
            resultSet.getInt("stroke_count"),
            resultSet.getString("radical"),
            resultSet.getInt("lesson_no"),
            resultSet.getInt("page_no"),
            resultSet.getString("edition"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 按字查出现过的所有课。同字多课是真实情形（如"底"先后出现在两课），
     * 返回整个列表并按课序排序，调用方拿到几条就报几条。
     */
    public List<HanziEntry> findByHanzi(String hanzi) {
        return jdbcTemplate.query(SELECT + " WHERE hanzi = ? ORDER BY lesson_no, edition",
                ROW_MAPPER, hanzi);
    }

    /**
     * 按课列出全部生字。库里没有“课本上的原始顺序”这一列，按字排序保证输出稳定可测。
     */
    public List<HanziEntry> findByLesson(int lessonNo) {
        return jdbcTemplate.query(SELECT + " WHERE lesson_no = ? ORDER BY hanzi",
                ROW_MAPPER, lessonNo);
    }

    /**
     * 已录入的课序范围（空表返回 empty）。课序越界的兜底提示要用：
     * "第 9 课没查到，表里录的是第 1~4 课"比干巴巴的"没查到"更能引导模型追问。
     */
    public Optional<int[]> lessonRange() {
        int[] range = jdbcTemplate.query(
                "SELECT MIN(lesson_no), MAX(lesson_no) FROM hanzi_entry",
                resultSet -> resultSet.next() && resultSet.getObject(1) != null
                        ? new int[] {resultSet.getInt(1), resultSet.getInt(2)}
                        : null);
        return Optional.ofNullable(range);
    }
}
