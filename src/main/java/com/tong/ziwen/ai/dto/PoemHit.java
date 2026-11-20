package com.tong.ziwen.ai.dto;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

/**
 * 裸检索命中项：出处字段 + 原文 + 相似度得分。
 */
public record PoemHit(String title, String author, String dynasty, String lesson,
                      String content, double score) {

    /** 从向量检索命中构造：出处取自条目 metadata，得分保留三位小数便于观察。 */
    public static PoemHit from(EmbeddingMatch<TextSegment> match) {
        Metadata metadata = match.embedded().metadata();
        return new PoemHit(
                metadata.getString("title"),
                metadata.getString("author"),
                metadata.getString("dynasty"),
                metadata.getString("lesson"),
                metadata.getString("content"),
                Math.round(match.score() * 1000) / 1000.0);
    }
}
