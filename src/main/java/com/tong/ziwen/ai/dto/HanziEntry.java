package com.tong.ziwen.ai.dto;

/**
 * 生字表条目：一个字在一版课本一课的完整事实（拼音、笔画、偏旁、课序、页码）。
 *
 * <p>同一个字在多课出现时会对应多条记录——"字→课"是一对多关系，
 * 查询结果必须是列表，不能只取第一行。</p>
 */
public record HanziEntry(String hanzi, String pinyin, int strokeCount, String radical,
                         int lessonNo, int pageNo, String edition) {

    /** 单条命中格式化为"第几课第几页（拼音、笔画、偏旁、版次）"：字段齐，模型答完自然带上出处。 */
    public String format() {
        return "第%d课 P%d（%s，%d画，%s部，%s）".formatted(
                lessonNo, pageNo, pinyin, strokeCount, radical, edition);
    }
}
