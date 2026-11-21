package com.tong.ziwen.ai.store;

import java.util.concurrent.ExecutionException;

import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.config.AiProperties;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Qdrant collection 的管理操作：清点条数、（重）建 collection。
 *
 * <p>为什么要写这个类，而不用 {@code EmbeddingStore}？因为 {@code EmbeddingStore} 接口只有"存、搜、删"，
 * "库里现在有几条""collection 建没建"等管理动作，接口不支持。
 * 所以这类操作只能拿数据库自己的客户端做。存储归接口、管理归客户端，两层各干各的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantCollectionAdmin {

    private final QdrantClient qdrantClient;
    private final AiProperties properties;

    /**
     * 数一下 collection 里现在存了几条向量。
     *
     * <p>首次启动时 collection 还不存在，直接 count 会被 Qdrant 报"不存在"，
     * 所以先问存在与否：不存在按 0 条算，调用方据此走"嵌入并入库"的分支。</p>
     */
    public long countPoints() {
        String collectionName = properties.getRag().getQdrant().getCollectionName();
        try {
            if (!qdrantClient.collectionExistsAsync(collectionName).get()) {
                return 0;
            }
            return qdrantClient.countAsync(collectionName).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("清点 Qdrant collection 被中断：" + collectionName, e);
        } catch (ExecutionException e) {
            // 最常见的原因就是 Qdrant 没起，把排查提示直接写进报错信息里
            throw new IllegalStateException(
                    "连不上 Qdrant（" + collectionName + "），请先检查原因再启动应用", e.getCause());
        }
    }

    /**
     * 重建 collection：已存在就删掉再建，不存在直接建，无论哪种情况结束都是一个空库。
     *
     * <p>不用客户端现成的 {@code recreateCollectionAsync}：它内部是"先删再建"两步链，
     * 可 collection 不存在时删除那步会直接抛异常（"could not be deleted"）——
     * 首次启动恰恰就是不存在的情况。所以这里拆开写：先问存在与否，存在才删，再建。</p>
     *
     * <p>维度不写死在配置里，由调用方从真实 embedding 里量出来——换 embedding 模型
     * （维度跟着变）时不会出现"配置写 1024、实际向量 1536"的错位。距离度量用 Cosine，
     * 与检索得分的口径一致。</p>
     */
    public void recreateCollection(int dimension) {
        String collectionName = properties.getRag().getQdrant().getCollectionName();
        try {
            if (qdrantClient.collectionExistsAsync(collectionName).get()) {
                qdrantClient.deleteCollectionAsync(collectionName).get();
            }
            qdrantClient.createCollectionAsync(collectionName,
                            Collections.VectorParams.newBuilder()
                                    .setDistance(Collections.Distance.Cosine)
                                    .setSize(dimension)
                                    .build())
                    .get();
            log.info("Qdrant collection「{}」已就绪（{} 维，Cosine）", collectionName, dimension);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("创建 Qdrant collection 被中断：" + collectionName, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant collection 创建失败：" + collectionName, e.getCause());
        }
    }
}
