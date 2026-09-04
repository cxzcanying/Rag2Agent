package com.rag2agent.bootstrap.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rag2agent.bootstrap.config.EmbeddingCacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 查询 Embedding 的二级缓存服务
 *
 * <p>采用 L1（本地 Caffeine）+ L2（Redis）双层缓存架构，用于缓存文本对应的向量表示。
 *
 * <p>缓存策略：
 * <ul>
 *   <li><b>L1 缓存（Caffeine）</b>：本地内存缓存，极快，适合高频访问的热点数据</li>
 *   <li><b>L2 缓存（Redis）</b>：分布式缓存，适合跨实例共享，命中率更高</li>
 *   <li><b>回源（Source）</b>：两级缓存都未命中时，调用 Embedding 服务生成向量</li>
 * </ul>
 *
 * <p>核心设计：
 * <ul>
 *   <li><b>请求合并 (Coalescing)</b>：同一个 Key 的并发请求只触发一次回源，其他请求等待结果</li>
 *   <li><b>缓存失败透明</b>：Redis 不可用时自动降级到本地缓存或直接回源，不影响检索功能</li>
 *   <li><b>不可变数据</b>：返回的向量使用 {@code List.copyOf()} 包装，防止被修改</li>
 * </ul>
 *
 * @author 21311
 */
@Service
public class QueryEmbeddingCache {

    /**
     * Jackson 类型引用，用于将 Redis 中的 JSON 数组反序列化为 {@code List<Float>}
     */
    private static final TypeReference<List<Float>> VECTOR_TYPE = new TypeReference<>() {};

    // ==================== 核心组件 ====================

    private final Cache<String, List<Float>> localCache;        // L1 本地缓存（Caffeine）
    private final StringRedisTemplate redis;                    // L2 分布式缓存（Redis）
    private final ObjectMapper objectMapper;                    // JSON 序列化/反序列化
    private final EmbeddingCacheProperties properties;          // 缓存配置（过期时间、容量等）
    private final MeterRegistry meterRegistry;                  // Micrometer 指标埋点

    /**
     * 正在进行的请求映射表
     *
     * <p>用于实现"请求合并"功能：
     * <ul>
     *   <li>Key：缓存 Key</li>
     *   <li>Value：正在执行中的 {@code CompletableFuture}</li>
     *   <li>当同一个 Key 的多个请求同时到达时，只有一个会触发回源，
     *       其他请求通过 {@code join()} 等待这个 Future 完成</li>
     * </ul>
     */
    private final ConcurrentMap<String, CompletableFuture<List<Float>>> inFlight = new ConcurrentHashMap<>();

    // ==================== 构造函数 ====================

    public QueryEmbeddingCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            EmbeddingCacheProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        // 初始化 Caffeine 本地缓存
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEntries())                    // 最大条目数
                .expireAfterAccess(Duration.ofSeconds(properties.getTtlSeconds()))  // 访问后过期
                .build();
    }

    // ==================== 核心方法：获取向量 ====================

    /**
     * 获取查询文本对应的向量表示
     *
     * <p>执行流程：
     * <ol>
     *   <li>检查缓存是否启用，未启用则直接回源</li>
     *   <li>生成缓存 Key（包含 provider、model、query 的 SHA-256）</li>
     *   <li>查询 L1 本地缓存，命中则直接返回</li>
     *   <li>L1 未命中，尝试请求合并：检查是否有其他线程正在加载同一个 Key</li>
     *   <li>如有正在加载的请求，等待其完成并返回结果</li>
     *   <li>如无，作为"加载者"从 L2 或回源加载数据</li>
     * </ul>
     *
     * @param provider Embedding 服务提供商（如 openai、aliyun）
     * @param model 使用的模型名称（如 text-embedding-ada-002）
     * @param query 用户查询文本
     * @param loader 回源加载器，当缓存未命中时调用
     * @return 查询文本对应的向量 {@code List<Float>}
     */
    public List<Float> getOrCompute(String provider, String model, String query, Supplier<List<Float>> loader) {
        // ========== 第一步：检查缓存是否启用 ==========
        // 如果缓存被禁用，直接调用 Embedding 服务，不做任何缓存
        if (!properties.isEnabled()) {
            return loader.get();
        }

        // ========== 第二步：生成缓存 Key ==========
        // Key 格式：embedding:{模型版本}:{维度}:{provider}:{model}:{query摘要}
        // 使用 SHA-256 对 query 做哈希，避免 query 过长或包含特殊字符
        String key = key(provider, model, query);

        // ========== 第三步：查询 L1 本地缓存 ==========
        List<Float> local = localCache.getIfPresent(key);
        if (local != null) {
            count("l1", "hit");    // 记录 L1 命中
            return local;
        }
        count("l1", "miss");       // 记录 L1 未命中

        // ========== 第四步：请求合并（解决缓存击穿） ==========
        // 创建一个新的 CompletableFuture 作为"加载任务"
        CompletableFuture<List<Float>> candidate = new CompletableFuture<>();

        // 原子性地放入 inFlight 映射表
        // 如果 Key 已存在（说明其他线程正在加载），返回已存在的 Future
        CompletableFuture<List<Float>> shared = inFlight.putIfAbsent(key, candidate);

        if (shared != null) {
            // 有其他线程正在加载同一个 Key → 等待它完成
            // join() 会阻塞直到加载完成或异常
            return shared.join();
        }

        // ========== 第五步：当前线程作为"加载者" ==========
        // putIfAbsent 成功了，说明当前线程是第一个到达的
        // 由它负责从 L2 或回源加载数据
        try {
            // 从 L2 缓存加载，L2 未命中则回源
            List<Float> vector = loadFromL2OrSource(key, loader);
            // 加载成功，完成 Future，唤醒所有等待的线程
            candidate.complete(vector);
            return vector;
        } catch (RuntimeException exception) {
            // 加载失败，将异常传播给所有等待的线程
            candidate.completeExceptionally(exception);
            throw exception;
        } finally {
            // 清除 inFlight 中的条目（无论成功还是失败）
            // 使用 remove(key, candidate) 确保只移除当前线程放入的条目
            // 防止移除被其他线程替换的条目
            inFlight.remove(key, candidate);
        }
    }

    /**
     * 批量获取文本向量，供入库阶段缓存"正文切片"向量。
     *
     * <p>语义与 {@link #getOrCompute} 一致，只是从"单个文本"扩展为"一批文本"：
     * <ul>
     *   <li>key 同样按 provider/model/modelVersion/dimension + 文本摘要隔离，模型升级后旧 key 不命中；</li>
     *   <li>只对未命中的文本调用一次 {@code batchLoader}，保持 Embedding API 的批量语义；</li>
     *   <li>切片向量是入库时按文档串行计算，跨文档重复片段的并发合并不是瓶颈，故不做请求合并。
     *       （ponytail: 若将来出现跨文档高并发重复片段，再补 in-flight 合并。）</li>
     * </ul>
     *
     * @param provider Embedding 服务提供商
     * @param model 使用的模型名称
     * @param texts 待向量化文本列表，顺序与返回向量一一对应
     * @param batchLoader 对"未命中文本"的批量回源加载器，返回与输入顺序一致的向量
     * @return 与 {@code texts} 一一对应的向量列表（元素不可变）
     */
    public List<List<Float>> getOrComputeBatch(String provider, String model, List<String> texts,
            Function<List<String>, List<List<Float>>> batchLoader) {
        if (!properties.isEnabled()) {
            return batchLoader.apply(texts);
        }
        int size = texts.size();
        List<String> keys = new ArrayList<>(size);
        for (String text : texts) {
            keys.add(key(provider, model, text));
        }

        // ======== L1 命中分发：命中的直接用，未命中的记下下标 ========
        List<List<Float>> result = new ArrayList<>(Collections.nCopies(size, null));
        List<Integer> missIndexes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            List<Float> local = localCache.getIfPresent(keys.get(i));
            if (local != null) {
                result.set(i, local);
                count("l1", "hit");
            } else {
                missIndexes.add(i);
                count("l1", "miss");
            }
        }
        if (missIndexes.isEmpty()) {
            return result;
        }

        // ======== L2 批量命中分发：multiGet 一次取回未命中的 key ========
        List<Integer> remaining = new ArrayList<>();
        List<String> missKeys = missIndexes.stream().map(keys::get).toList();
        try {
            List<String> cached = redis.opsForValue().multiGet(missKeys);
            for (int i = 0; i < missIndexes.size(); i++) {
                String json = cached.get(i);
                if (json == null) {
                    remaining.add(missIndexes.get(i));
                    count("l2", "miss");
                } else {
                    List<Float> vector = List.copyOf(objectMapper.readValue(json, VECTOR_TYPE));
                    localCache.put(keys.get(missIndexes.get(i)), vector);
                    result.set(missIndexes.get(i), vector);
                    count("l2", "hit");
                }
            }
        } catch (Exception ignored) {
            // Redis 异常（连接、序列化失败等）→ 降级，全部未命中交给回源；不影响入库
            remaining.addAll(missIndexes);
            count("l2", "error");
        }
        if (remaining.isEmpty()) {
            return result;
        }

        // ======== 回源：只对仍未命中的文本调一次批量加载 ========
        List<String> remainingTexts = remaining.stream().map(texts::get).toList();
        List<List<Float>> loaded = batchLoader.apply(remainingTexts);
        if (loaded.size() != remaining.size()) {
            throw new IllegalStateException("Embedding 批量返回数量不匹配: 期望 " + remaining.size()
                    + " 条，实际 " + loaded.size() + " 条");
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<Float> vector = List.copyOf(loaded.get(i));
            int index = remaining.get(i);
            result.set(index, vector);
            localCache.put(keys.get(index), vector);
            try {
                redis.opsForValue().set(keys.get(index), objectMapper.writeValueAsString(vector),
                        Duration.ofSeconds(properties.getTtlSeconds()));
            } catch (Exception ignored) {
                // Redis 写入失败只记录指标，不影响返回；下次请求重新回源
                count("l2", "write_error");
            }
        }
        return result;
    }

    // ==================== L2 缓存或回源加载 ====================

    /**
     * 从 L2（Redis）缓存加载向量，未命中则回源（调用 Embedding 服务）
     *
     * <p>加载策略：
     * <ol>
     *   <li>查询 Redis，如果命中 → 反序列化 → 写入 L1 → 返回</li>
     *   <li>Redis 未命中 → 调用 {@code loader.get()} 回源生成向量</li>
     *   <li>回源成功后 → 写入 L1 和 L2 → 返回</li>
     *   <li>Redis 发生异常 → 降级，直接回源（不阻断业务）</li>
     *   <li>Redis 写入失败 → 记录指标，不影响返回（缓存失败透明）</li>
     * </ol>
     *
     * @param key 缓存 Key
     * @param loader 回源加载器
     * @return 向量列表（不可变，通过 {@code List.copyOf()} 包装）
     */
    private List<Float> loadFromL2OrSource(String key, Supplier<List<Float>> loader) {
        // ========== 第一步：尝试从 L2（Redis）加载 ==========
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                // Redis 命中 → 反序列化 JSON 为 List<Float>
                List<Float> vector = List.copyOf(objectMapper.readValue(cached, VECTOR_TYPE));
                // 写入 L1 本地缓存，加速后续访问
                localCache.put(key, vector);
                count("l2", "hit");
                return vector;
            }
            count("l2", "miss");
        } catch (Exception ignored) {
            // Redis 异常（连接超时、序列化失败等）→ 记录指标，降级到回源
            // 不抛出异常，避免影响主业务
            count("l2", "error");
        }

        // ========== 第二步：L2 未命中 → 回源 ==========
        // 调用 Embedding 服务生成向量
        List<Float> vector = List.copyOf(loader.get());

        // 写入 L1 本地缓存
        localCache.put(key, vector);

        // ========== 第三步：异步写入 L2（Redis） ==========
        try {
            // 将向量序列化为 JSON 字符串
            String json = objectMapper.writeValueAsString(vector);
            // 写入 Redis，设置过期时间
            redis.opsForValue().set(key, json, Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (Exception ignored) {
            // Redis 写入失败 → 只记录指标，不影响返回
            // 下次请求会重新从 L2 读取或回源
            count("l2", "write_error");
        }

        return vector;
    }

    // ==================== 指标埋点 ====================

    /**
     * 记录缓存访问指标
     *
     * <p>指标名：{@code rag2agent.cache.requests}
     * <p>标签：
     * <ul>
     *   <li>{@code cache=embedding}：缓存类型</li>
     *   <li>{@code level=l1|l2}：缓存层级</li>
     *   <li>{@code outcome=hit|miss|error|write_error}：访问结果</li>
     * </ul>
     *
     * @param level 缓存层级（l1 或 l2）
     * @param outcome 访问结果（hit、miss、error、write_error）
     */
    private void count(String level, String outcome) {
        meterRegistry.counter("rag2agent.cache.requests",
                        "cache", "embedding",
                        "level", level,
                        "outcome", outcome)
                .increment();
    }

    // ==================== 工具方法 ====================

    /**
     * 生成缓存 Key。Key 格式：{@code embedding:{modelVersion}:{dimension}:{provider}:{model}:{sha256(text)}}。
     *
     * <p>模型版本/维度参与拼 key：换模型或改维度后自然隔离，不会把旧模型的向量当新模型用。
     */
    private String key(String provider, String model, String text) {
        return "embedding:" + properties.getModelVersion() + ":" + properties.getDimension() + ":"
                + provider + ":"
                + (model == null ? "default" : model) + ":"
                + sha256(text.trim());
    }

    /**
     * 计算字符串的 SHA-256 哈希值（十六进制字符串）
     *
     * <p>用途：将查询文本转换为固定长度的哈希值，作为缓存 Key 的一部分。
     *
     * <p>为什么用 SHA-256：
     * <ul>
     *   <li>保证相同 query 生成相同 Key（确定性）</li>
     *   <li>固定长度（64 字符），避免 query 过长导致 Key 过长</li>
     *   <li>避免 query 中的特殊字符（空格、换行等）破坏 Key 格式</li>
     *   <li>几乎没有哈希冲突的可能</li>
     * </ul>
     *
     * @param value 原始字符串
     * @return SHA-256 哈希值（十六进制小写）
     * @throws IllegalStateException JDK 不支持 SHA-256 算法时抛出
     */
    private String sha256(String value) {
        try {
            // 计算 SHA-256 摘要
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            // 转换为十六进制字符串
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 标准算法，理论上不会发生
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
