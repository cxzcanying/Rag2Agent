package com.rag2agent.infra.ai.client.impl;

import com.rag2agent.infra.ai.exception.AiClientException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 只为无副作用的模型请求提供有限重试；工具调用不得复用此执行器。 */
final class AiHttpExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 200;

    private AiHttpExecutor() {}

    static Response execute(OkHttpClient client, Request request, String operation) throws IOException {
        AiClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response;
                }
                String body = response.body() == null ? "" : response.body().string();
                int status = response.code();
                long retryAfter = parseRetryAfter(response.header("Retry-After"));
                response.close();
                AiClientException.Kind kind = status == 429
                        ? AiClientException.Kind.RATE_LIMITED
                        : status >= 500
                                ? AiClientException.Kind.UPSTREAM_ERROR
                                : AiClientException.Kind.CLIENT_ERROR;
                lastFailure = new AiClientException(
                        operation + " API 错误 " + status + ": " + body, kind, status, retryAfter);
                if (!isRetryable(lastFailure) || attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
            } catch (IOException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new AiClientException(
                            operation + " 调用超时或网络失败: " + exception.getMessage(),
                            exception, AiClientException.Kind.TIMEOUT, 0, 0);
                }
            }
            sleepBeforeRetry();
        }
        throw lastFailure == null
                ? new AiClientException(operation + " 调用失败")
                : lastFailure;
    }

    private static boolean isRetryable(AiClientException exception) {
        return exception.kind() == AiClientException.Kind.RATE_LIMITED
                || exception.kind() == AiClientException.Kind.UPSTREAM_ERROR;
    }

    private static void sleepBeforeRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseRetryAfter(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
