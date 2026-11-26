package com.tong.ziwen.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tong.ziwen.common.Response;
import com.tong.ziwen.ai.dto.Poem;
import com.tong.ziwen.ai.dto.PoemHit;
import com.tong.ziwen.ai.service.PoemService;
import lombok.RequiredArgsConstructor;

/**
 * 古诗库接口：语料清单 + 裸检索。
 */
@RestController
@RequestMapping("/api/poems")
@RequiredArgsConstructor
public class PoemController {

    private final PoemService poemService;

    /** 语料全量列表：对照课本录入的原始数据。 */
    @GetMapping
    public Response<List<Poem>> list() {
        return Response.ok(poemService.all());
    }

    /** 裸检索：只走向量检索、不调 chat 模型，返回命中的古诗和相似度得分。 */
    @GetMapping("/search")
    public Response<List<PoemHit>> search(@RequestParam String question) {
        return Response.ok(poemService.search(question));
    }
}
