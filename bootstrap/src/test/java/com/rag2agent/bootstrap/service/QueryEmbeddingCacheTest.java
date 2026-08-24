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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
