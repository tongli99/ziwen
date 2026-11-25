package com.tong.ziwen.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.tong.ziwen.ai.service.HanziService;

/**
 * 生字表工具测试：验证的是"给模型看的输出"——命中话术带出处、空结果话术引导追问、
 * 入参防御消化模型噪声，全程不依赖真实模型（数据库用内存 SQLite）。
 */
class HanziToolTest {

    private HanziTool hanziTool;

    @BeforeEach
    void setUpInMemorySqlite() throws SQLException {
        // 与 HanziServiceTest 同一套内存库脚手架：执行仓库里同一份 schema.sql / data.sql
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource("data.sql"), StandardCharsets.UTF_8));
        hanziTool = new HanziTool(new HanziService(
                new JdbcTemplate(new SingleConnectionDataSource(connection, true))));
    }

    @Test
    void charLookupCarriesLessonAndPageForTheModel() {
        // 模型拿到的是"第几课第几页 + 拼音笔画偏旁"，信息够它答完自然带上出处
        String result = hanziTool.findLessonsByHanzi("灌", "test-session");

        assertThat(result)
                .contains("「灌」")
                .contains("第2课")
                .contains("P6")
                .contains("guàn")
                .contains("20画")
                .contains("氵部")
                .contains("统编版二上·2024");
    }

    @Test
    void charLookupReportsEveryLessonForRepeatedChar() {
        // 同字多课："底"要报全两处，不能只取第一行
        String result = hanziTool.findLessonsByHanzi("底", "test-session");

        assertThat(result)
                .contains("出现 2 次")
                .contains("第3课")
                .contains("第4课");
    }

    @Test
    void charLookupKeepsMultiPronunciation() {
        // 多音字不删读法："为"的两个读音都带回，模型才能按语境选对
        String result = hanziTool.findLessonsByHanzi("为", "test-session");

        assertThat(result).contains("wéi/wèi");
    }

    @Test
    void unknownCharGetsHonestNotFound() {
        // 库外字老实说没有，话术里明确"不要猜是哪一课"
        String result = hanziTool.findLessonsByHanzi("囧", "test-session");

        assertThat(result).contains("没有「囧」").contains("不要猜");
    }

    @Test
    void missingCharParameterAsksBackInsteadOfGuessing() {
        // 参数抽取失败的兜底：抽不出汉字就回引导文本，让模型去问孩子
        assertThat(hanziTool.findLessonsByHanzi(null, "test-session"))
                .contains("先问清楚孩子想问哪个字");
        assertThat(hanziTool.findLessonsByHanzi("guan", "test-session"))
                .contains("先问清楚孩子想问哪个字");
    }

    @Test
    void lessonLookupListsTheWholeLesson() {
        // 验收例："第 3 课要预习哪些字"——字表列全，拼音笔画偏旁一样不少
        String result = hanziTool.listCharsByLesson(3, "test-session");

        assertThat(result)
                .contains("第 3 课共 15 个生字")
                .contains("植 zhí")
                .contains("P11 起");
    }

    @Test
    void outOfRangeLessonAdviceCarriesTheRecordedRange() {
        // 课序越界的兜底：带上"录的是第 1~4 课"，引导模型跟孩子确认课序
        String result = hanziTool.listCharsByLesson(99, "test-session");

        assertThat(result).contains("第 99 课没查到生字").contains("第 1~4 课");
    }

    @Test
    void firstHanCharacterDigestsModelNoise() {
        // 入参防御：模型常把"灌"说成"灌字"甚至整句话——取第一个汉字
        assertThat(HanziTool.firstHanCharacter("灌字")).isEqualTo("灌");
        assertThat(HanziTool.firstHanCharacter("灌溉的灌")).isEqualTo("灌");
        assertThat(HanziTool.firstHanCharacter("  ")).isNull();
        assertThat(HanziTool.firstHanCharacter("")).isNull();
    }
}
