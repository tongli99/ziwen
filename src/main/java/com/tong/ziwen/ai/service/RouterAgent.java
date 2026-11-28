package com.tong.ziwen.ai.service;

import dev.langchain4j.service.UserMessage;

/**
 * 分诊台：把孩子的每句话归入 背诗 / 查字 / 闲聊 三类，供编排层分发。
 *
 * <p>分类只看当前这句话、不看对话历史，也不带记忆、不带工具，一次同步调用完事即弃。
 * 代价是"再背一遍刚才那首"这类指代问句分不出背诗意图、会被归入闲聊——闲聊分支
 * 走的是全工具兜底伴读，误分诊也能接着办，只是丢了专才的专注。</p>
 */
public interface RouterAgent {

    /**
     * 三分类。返回类型是枚举，框架会在提示词里追加"只回答枚举值之一"的约束，
     * 模型的回答直接映射回 {@link Intent}。
     */
    Intent classify(@UserMessage String question);
}
