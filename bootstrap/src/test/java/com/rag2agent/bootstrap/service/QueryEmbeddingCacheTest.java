package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.EmbeddingCacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryEmbeddingCacheTest {

    @Test
    void concurrentMissesShareOneLoaderCall() throws Exception {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(true);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> cache.getOrCompute(
                            "provider", "model", "同一个查询", () -> {
                                calls.incrementAndGet();
                                await(release);
                                return List.of(1.0f, 2.0f);
                            })))
                    .toList();
            release.countDown();
            for (var future : futures) {
                assertEquals(List.of(1.0f, 2.0f), future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedLoadIsNotRetained() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.getOrCompute(
                "provider", "model", "失败查询", () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }));
        assertEquals(List.of(3.0f), cache.getOrCompute(
                "provider", "model", "失败查询", () -> {
                    calls.incrementAndGet();
                    return List.of(3.0f);
                }));
        assertEquals(2, calls.get());
    }

    @Test
    void modelVersionAndDimensionIsolateEntries() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(true);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        cache.getOrCompute("provider", "model", "query", () -> {
            calls.incrementAndGet();
            return List.of(1.0f);
        });
        properties.setModelVersion("v2");
        cache.getOrCompute("provider", "model", "query", () -> {
            calls.incrementAndGet();
            return List.of(2.0f);
        });
        properties.setDimension(2048);
        cache.getOrCompute("provider", "model", "query", () -> {
            calls.incrementAndGet();
            return List.of(3.0f);
        });
        assertEquals(3, calls.get());
    }

    @Test
    void batchMissesShareOneLoaderCall() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(true);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        List<String> texts = List.of("a", "b", "c");

        List<List<Float>> result = cache.getOrComputeBatch("provider", "model", texts, missing -> {
            calls.incrementAndGet();
            assertEquals(texts, missing);
            return List.of(List.of(1.0f), List.of(2.0f), List.of(3.0f));
        });

        assertEquals(1, calls.get());
        assertEquals(List.of(1.0f), result.get(0));
        assertEquals(List.of(2.0f), result.get(1));
        assertEquals(List.of(3.0f), result.get(2));
    }

    @Test
    void batchReusesCachedTextAndOnlyComputesMissing() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(true);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        cache.getOrComputeBatch("provider", "model", List.of("a", "b", "c"),
                missing -> List.of(List.of(1.0f), List.of(2.0f), List.of(3.0f)));
        AtomicInteger calls = new AtomicInteger();

        List<List<Float>> result = cache.getOrComputeBatch("provider", "model", List.of("a", "x", "c"),
                missing -> {
                    calls.incrementAndGet();
                    assertEquals(List.of("x"), missing);
                    return List.of(List.of(9.0f));
                });

        assertEquals(1, calls.get());
        assertEquals(List.of(1.0f), result.get(0));
        assertEquals(List.of(9.0f), result.get(1));
        assertEquals(List.of(3.0f), result.get(2));
    }

    @Test
    void batchModelVersionIsolatesEntries() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(true);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        cache.getOrComputeBatch("provider", "model", List.of("q"), missing -> {
            calls.incrementAndGet();
            return List.of(List.of(1.0f));
        });
        properties.setModelVersion("v2");
        cache.getOrComputeBatch("provider", "model", List.of("q"), missing -> {
            calls.incrementAndGet();
            return List.of(List.of(2.0f));
        });
        assertEquals(2, calls.get());
    }

    @Test
    void batchDisabledBypassesCache() {
        EmbeddingCacheProperties properties = new EmbeddingCacheProperties();
        properties.setEnabled(false);
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        cache.getOrComputeBatch("provider", "model", List.of("a", "b"), missing -> {
            calls.incrementAndGet();
            return List.of(List.of(1.0f), List.of(2.0f));
        });
        assertEquals(1, calls.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
