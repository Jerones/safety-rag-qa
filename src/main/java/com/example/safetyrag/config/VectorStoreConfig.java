package com.example.safetyrag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 向量库配置：Qdrant。
 *
 * <p>与之前 Chroma 版本的关键差别，也是这次迁移的主要收益：</p>
 *
 * <ul>
 *   <li><b>集合必须显式建，且必须指定维度。</b>Chroma 是插入时才确定维度，Qdrant 在
 *       {@code createCollection} 时就把 {@code size} 定死。好处是维度不一致能在启动阶段
 *       就报错（下面 {@code ensureCollection} 会拿集合实际维度和配置比对），坏处是
 *       换 embedding 模型必须改配置并重建集合。</li>
 *   <li><b>不需要"靠错误文案判断集合是否存在"。</b>Qdrant 有 {@code collectionExists}
 *       这类明确接口。Chroma 那边 Spring AI 1.0.0 拿服务端错误字符串做精确比对，
 *       真实文案对不上，导致空库永远启动失败——那 100 多行绕坑代码在这里直接消失。</li>
 *   <li><b>删除幂等。</b>Qdrant 删不存在的 id 不报错、不崩溃，所以
 *       {@code KnowledgeBaseService} 里"先求交集再删"的防御可以退化成"直接按 docId 删"。</li>
 * </ul>
 *
 * <p>装配仍然自己来（不交给自动配置），原因只有一个：自动建集合用的是
 * {@code embeddingModel.dimensions()}，而 OpenAI 兼容端点的该方法默认返回 1536，
 * 与实际 1024 维不符，会建出一个写不进去的集合。</p>
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String host;

    /** gRPC 端口（6334），不是 REST 的 6333。 */
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:safety-regulations}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

    private final AppProperties appProperties;

    /** 实际使用的地址，仅用于报错提示。 */
    private String endpoint;

    public VectorStoreConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 带批量预取的 Embedding 装饰器（进程内一份）。
     *
     * <p>入库时 VectorStore 会把整批文档交给 EmbeddingModel，而这条路径默认是一条
     * 文本一次请求。入库前先按 5 条/批把整篇算好放进缓存，后续逐条调用全部命中，
     * 171 块的文档从 171 次 HTTPS 降到 35 次，详见 {@link CachingEmbeddingModel}。</p>
     */
    private static volatile CachingEmbeddingModel cachedEmbeddingModel;

    /**
     * 入库前批量预热 embedding。拿不到模型时返回 0，调用方不必判空。
     */
    public static int prefetchEmbeddings(List<String> texts) {
        CachingEmbeddingModel model = cachedEmbeddingModel;
        if (model == null || texts == null || texts.isEmpty()) {
            return 0;
        }
        return model.prefetch(texts);
    }

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        this.endpoint = host + ":" + port;
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        log.info("Qdrant 服务地址：{}（gRPC{}），集合：{}", endpoint,
                useTls ? "，TLS" : "", collectionName);
        return new QdrantClient(builder.build());
    }

    @Bean
    public VectorStore qdrantVectorStore(QdrantClient qdrantClient, EmbeddingModel embeddingModel) {
        CachingEmbeddingModel cachedModel = new CachingEmbeddingModel(embeddingModel);
        cachedEmbeddingModel = cachedModel;
        int dimension = appProperties.vectorDimensionOrDefault();
        try {
            ensureCollection(qdrantClient, dimension);
        } catch (RuntimeException e) {
            throw new IllegalStateException(failureHint(e), e);
        }
        VectorStore store = QdrantVectorStore.builder(qdrantClient, cachedModel)
                .collectionName(collectionName)
                // 集合由上面 ensureCollection 负责，关掉 Spring AI 的自动建表
                // （它按 embeddingModel.dimensions() 建，OpenAI 兼容端点会给出 1536）
                .initializeSchema(false)
                .build();
        log.info("当前 VectorStore 实现：QdrantVectorStore（远端 gRPC 服务，无本地向量文件）");
        return store;
    }

    /**
     * 确保集合存在，并校验维度与配置一致。
     *
     * <p>维度校验是 Qdrant 版新增的一道启动检查。Chroma 不关心你写多少维，
     * 混进去只会让检索悄悄变差；Qdrant 则会在写入时报错——与其等到用户上传文档
     * 才发现，不如在启动时就把"配置和库里维度不一致"这件事说清楚。</p>
     */
    private void ensureCollection(QdrantClient client, int dimension) {
        try {
            if (!client.collectionExistsAsync(collectionName).get()) {
                client.createCollectionAsync(collectionName, VectorParams.newBuilder()
                        .setSize(dimension)
                        .setDistance(Distance.Cosine)
                        .build()).get();
                ensurePayloadIndexes(client);
                log.info("已创建 Qdrant 集合：{}（维度 {}，距离 Cosine）", collectionName, dimension);
                return;
            }
            CollectionInfo info = client.getCollectionInfoAsync(collectionName).get();
            long actual = info.getConfig().getParams().getVectorsConfig().getParams().getSize();
            if (actual != dimension) {
                throw new IllegalStateException(dimensionMismatchHint(actual, dimension));
            }
            log.info("Qdrant 集合已就绪：{}（{} 个点，维度 {}）",
                    collectionName, info.getPointsCount(), actual);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("初始化 Qdrant 集合时被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("初始化 Qdrant 集合失败：" + rootMessage(e), e);
        }
    }

    /**
     * 建 payload 索引。
     *
     * <p>这三个字段都有明确的用途，不是为了"看起来完整"：</p>
     * <ul>
     *   <li>{@code docId}（keyword）—— 检索时限定文档范围的下推过滤，
     *       以及"查看某篇文档的全部块"的 scroll 过滤；</li>
     *   <li>{@code chunkIndex}（integer）—— 按条款顺序 scroll 时要 order_by，
     *       Qdrant 的 order_by 必须有 range 索引；</li>
     *   <li>{@code clause}（keyword）—— 引用卡片按条款号定位、后续做条款级统计。</li>
     * </ul>
     *
     * <p>只在集合刚创建时建一次：Qdrant 的快照会连同索引一起恢复，重复建虽然幂等，
     * 但每次启动多发 3 个 gRPC 请求没有意义。</p>
     */
    private void ensurePayloadIndexes(QdrantClient client) {
        indexField(client, "docId", FieldType.FieldTypeKeyword);
        indexField(client, "chunkIndex", FieldType.FieldTypeInteger);
        indexField(client, "clause", FieldType.FieldTypeKeyword);
    }

    private void indexField(QdrantClient client, String field, FieldType type) {
        try {
            // wait=true：建完索引再返回，避免紧接着写入时索引还没生效
            client.createPayloadIndexAsync(CreateFieldIndexCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setFieldName(field)
                    .setFieldType(type)
                    .build(), Duration.ofSeconds(30)).get();
            log.info("已创建 payload 索引：{}（{}）", field, type.name().replace("FieldType", ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // 索引缺失只影响过滤性能，不影响正确性，不该阻断启动
            log.warn("payload 索引 {} 创建失败（不影响检索正确性，仅影响过滤性能）：{}",
                    field, rootMessage(e));
        }
    }

    /**
     * 维度不一致时的提示。这是 Qdrant 版新增、Chroma 版不存在的一类故障：
     * 换了 embedding 模型但没重建集合。
     */
    private String dimensionMismatchHint(long actual, int configured) {
        return """
                Qdrant 集合 %s 的向量维度是 %d，但当前配置 app.vector-dimension=%d。

                这两者必须一致：向量维度是 embedding 模型决定的，混写会让检索结果全错。
                处理方式（二选一）：
                  1) 把 app.vector-dimension 改回 %d（或设环境变量 EMBEDDING_DIMENSION=%d）
                  2) 确认要换模型，则删除集合后在页面点「重建索引」：
                     curl -X DELETE http://%s:6333/collections/%s
                """.formatted(collectionName, actual, configured, actual, actual,
                host, collectionName);
    }

    /**
     * 把底层异常翻译成一句能直接照做的提示。
     *
     * <p>连不上 Qdrant 是最常见的启动失败：容器没起、只映射了 6333 没映射 6334、
     * 虚拟机没开机。默认的堆栈里只有一句 UNAVAILABLE，对使用者没有指导意义。</p>
     */
    private String failureHint(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        boolean unreachable = msg.contains("UNAVAILABLE")
                || msg.contains("Connection refused")
                || msg.contains("failed to connect")
                || msg.contains("UnknownHost")
                || msg.contains("timed out");
        if (!unreachable) {
            return "初始化 Qdrant 集合失败：" + msg;
        }
        return """
                连不上 Qdrant（%s）。向量库是这个应用的必要依赖，服务没起来就启动不了。

                处理方式：
                  1) 确认容器在跑：docker ps | grep qdrant
                  2) 确认 6334（gRPC）端口已映射——只开 6333 时 dashboard 能打开但应用连不上
                  3) 远端部署时指定地址：QDRANT_HOST=<ip> bash restart.sh
                  4) 自检：curl http://%s:6333/healthz
                详见 README「向量库（Qdrant）」一节。""".formatted(endpoint, host);
    }

    /** 取最内层 cause 的 message，Qdrant 的 gRPC 异常真实原因在 cause 里。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
