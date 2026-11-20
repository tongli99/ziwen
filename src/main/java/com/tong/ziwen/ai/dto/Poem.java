package com.tong.ziwen.ai.dto;

/**
 * 古诗条目：只存事实性字段——题目、作者、朝代、原文、年级课序。
 *
 * <p>版权红线：释义、赏析、译文一律不入库，由 LLM 运行时生成；
 * 入库作品要求作者卒年距今满 50 年（公版），批量校验脚本是番外篇的内容。</p>
 */
public record Poem(String title, String author, String dynasty, String content, String lesson) {
}
