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
import java.util.List;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 查询 Embedding 的 L1 Caffeine + L2 Redis 缓存；缓存失败时透明回源，不影响检索。 */
@Service
public class QueryEmbeddingCache {

    private static final TypeReference<List<Float>> VECTOR_TYPE = new TypeReference<>() {};

    private final Cache<String, List<Float>> localCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EmbeddingCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public QueryEmbeddingCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            EmbeddingCacheProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEntries())
                .expireAfterAccess(Duration.ofSeconds(properties.getTtlSeconds()))
                .build();
    }

    public List<Float> getOrCompute(String provider, String model, String query, Supplier<List<Float>> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        String key = "embedding:v1:" + provider + ":" + (model == null ? "default" : model) + ":" + sha256(query.trim());
        List<Float> local = localCache.getIfPresent(key);
        if (local != null) {
            count("l1", "hit");
            return local;
        }
        count("l1", "miss");
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                List<Float> vector = List.copyOf(objectMapper.readValue(cached, VECTOR_TYPE));
                localCache.put(key, vector);
                count("l2", "hit");
                return vector;
            }
            count("l2", "miss");
        } catch (Exception ignored) {
            count("l2", "error");
        }
        List<Float> vector = List.copyOf(loader.get());
        localCache.put(key, vector);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(vector), Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (Exception ignored) {
            count("l2", "write_error");
        }
        return vector;
    }

    private void count(String level, String outcome) {
        meterRegistry.counter("rag2agent.cache.requests", "cache", "embedding", "level", level, "outcome", outcome).increment();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
