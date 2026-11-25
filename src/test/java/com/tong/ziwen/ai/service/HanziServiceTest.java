package com.tong.ziwen.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.support.EncodedResource;

import com.tong.ziwen.ai.dto.HanziEntry;

/**
 * 生字表查询测试：跑在内存 SQLite 上，执行的是仓库里同一份 schema.sql / data.sql，
 * 白名单 SQL 的真实行为（精确匹配、同字多课、课序范围）全被覆盖。
 */
class HanziServiceTest {

    private HanziService hanziService;

    @BeforeEach
    void setUpInMemorySqlite() throws SQLException {
        // SQLite 的内存库按连接隔离：SingleConnectionDataSource 全程复用同一连接，
        // 建的表和灌的数据才不会在下一 getConnection 时消失
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("data.sql"), StandardCharsets.UTF_8));
        hanziService = new HanziService(
                new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
    }

    @Test
    void findByHanziHitsTheExactLesson() {
        // 验收例：问"灌字哪课学的"——精确匹配直接命中第 2 课
        List<HanziEntry> hits = hanziService.findByHanzi("灌");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).lessonNo()).isEqualTo(2);
        assertThat(hits.get(0).pinyin()).isEqualTo("guàn");
    }

    @Test
    void findByHanziReturnsAllLessonsForRepeatedChar() {
        // 同字多课是真实情形："底"先后出现在第 3、4 课，返回列表而不是只有第一行
        List<HanziEntry> hits = hanziService.findByHanzi("底");

        assertThat(hits).extracting(HanziEntry::lessonNo).containsExactly(3, 4);
    }

    @Test
    void findByHanziReturnsEmptyForUnknownChar() {
        // 形近字/库外字：精确匹配不近似——查不到就是空，这是结构化查询的本分
        assertThat(hanziService.findByHanzi("囧")).isEmpty();
    }

    @Test
    void findByLessonListsAllCharsSorted() {
        // 验收例：问"第 3 课有哪些字"——列全，且按字排序输出稳定
        List<HanziEntry> hits = hanziService.findByLesson(3);

        assertThat(hits).hasSize(15);
        assertThat(hits).extracting(HanziEntry::hanzi).contains("植", "为", "得");
        assertThat(hits).extracting(HanziEntry::hanzi).isSorted();
    }

    @Test
    void lessonRangeCoversAllRecordedLessons() {
        // 课序越界兜底的数据源：范围取自全表，不是写死的常量
        Optional<int[]> range = hanziService.lessonRange();

        assertThat(range).isPresent();
        assertThat(range.get()).containsExactly(1, 4);
    }
}
