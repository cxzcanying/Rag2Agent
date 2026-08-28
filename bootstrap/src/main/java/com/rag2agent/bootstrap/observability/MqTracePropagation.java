package com.rag2agent.bootstrap.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collections;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * 通过 RocketMQ user properties 传播标准 W3C traceparent/tracestate。
 * @author 21311
 * */
public final class MqTracePropagation {

    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapSetter<Message> SETTER = (carrier, key, value) -> {
        if (carrier != null && value != null) {
            carrier.putUserProperty(key, value);
        }
    };
    private static final TextMapGetter<MessageExt> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(MessageExt carrier) {
            return carrier == null ? Collections.emptyList() : carrier.getProperties().keySet();
        }

        @Override
        public String get(MessageExt carrier, String key) {
            return carrier == null ? null : carrier.getUserProperty(key);
        }
    };

    private MqTracePropagation() {}

    public static void inject(Message message) {
        PROPAGATOR.inject(Context.current(), message, SETTER);
    }

    public static Context extract(MessageExt message) {
        return PROPAGATOR.extract(Context.current(), message, GETTER);
    }
}
