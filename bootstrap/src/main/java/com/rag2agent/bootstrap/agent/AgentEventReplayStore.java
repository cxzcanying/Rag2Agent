package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 持久化 Agent SSE 事件，支持客户端断线后按 Last-Event-ID 增量回放。 */
@Service
public class AgentEventReplayStore {

    private static final Duration EVENT_TTL = Duration.ofHours(2);
    private static final long MAX_EVENTS = 4096;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AgentEventReplayStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public String append(long runId, AgentEvent event) {
        try {
            String key = key(runId);
            RecordId id = redis.opsForStream().add(StreamRecords.newRecord()
                    .in(key)
                    .ofMap(Map.of("event", objectMapper.writeValueAsString(event))));
            redis.opsForStream().trim(key, MAX_EVENTS, true);
            redis.expire(key, EVENT_TTL);
            return id == null ? null : id.getValue();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent SSE 事件序列化失败", exception);
        }
    }

    public List<StoredEvent> readAfter(long runId, String lastEventId, int count) {
        String cursor = lastEventId == null || lastEventId.isBlank() ? "0-0" : lastEventId;
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(count),
                org.springframework.data.redis.connection.stream.StreamOffset.create(key(runId), ReadOffset.from(cursor)));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(record -> new StoredEvent(record.getId().getValue(), String.valueOf(record.getValue().get("event"))))
                .toList();
    }

    private String key(long runId) {
        return "rag2agent:agent:events:" + runId;
    }

    public record StoredEvent(String id, String payload) {}
}
