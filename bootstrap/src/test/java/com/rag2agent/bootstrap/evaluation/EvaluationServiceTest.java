package com.rag2agent.bootstrap.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import com.rag2agent.bootstrap.dto.EvaluationDtos.EvaluationConfig;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSubmission;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.infra.ai.client.ChatModelClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EvaluationServiceTest {

    @Test
    void concurrentSameKeyCreatesOneRunAndDifferentPayloadIsRejected() throws Exception {
        FakeRepository repository = new FakeRepository();
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        EvaluationService service = service(repository, tasks::add);
        EvaluationConfig config = config(3600);
        CountDownLatch start = new CountDownLatch(1);
        List<RunSubmission> submissions = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable submit = () -> {
            try {
                start.await();
                submissions.add(service.submit(1L, "same", config, "key-1"));
            } catch (Throwable error) {
                errors.add(error);
            }
        };
        Thread first = new Thread(submit);
        Thread second = new Thread(submit);
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertTrue(errors.isEmpty());
        assertEquals(2, submissions.size());
        assertEquals(1, repository.createdCount.get());
        assertEquals(1, tasks.size());
        assertTrue(submissions.stream().anyMatch(RunSubmission::reused));
        com.rag2agent.framework.exception.BusinessException conflict = assertThrows(
                com.rag2agent.framework.exception.BusinessException.class,
                () -> service.submit(1L, "different", config, "key-1"));
        assertEquals("400", conflict.errorCode().code());
        assertEquals("Idempotency-Key 已用于不同的评测请求", conflict.getMessage());
    }

    @Test
    void submissionReturnsBeforeWorkerAndWorkerContinuesAfterClientDisconnect() {
        FakeRepository repository = new FakeRepository();
        List<Runnable> tasks = new ArrayList<>();
        EvaluationService service = service(repository, tasks::add);

        RunSubmission submission = service.submit(1L, "background", config(3600), "key-2");

        assertEquals("QUEUED", submission.status());
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        assertEquals("COMPLETED", repository.status);
        assertEquals(1, repository.results.size());
    }

    @Test
    void expiredRunBecomesTimeout() {
        FakeRepository repository = new FakeRepository();
        repository.startedAt = Instant.now().minusSeconds(10);
        EvaluationService service = service(repository, Runnable::run);

        service.submit(1L, "timeout", config(1), null);

        assertEquals("TIMEOUT", repository.status);
        assertEquals(0, repository.results.size());
    }

    @Test
    void invalidWorkerConfigurationBecomesFailed() {
        FakeRepository repository = new FakeRepository();
        repository.invalidConfig = true;
        EvaluationService service = service(repository, Runnable::run);

        service.submit(1L, "failure", config(3600), null);

        assertEquals("FAILED", repository.status);
        assertTrue(repository.errorMessage != null && !repository.errorMessage.isBlank());
    }

    @Test
    void missingRunIdIsNotFound() {
        EvaluationService service = service(new MissingRunRepository(), Runnable::run);

        com.rag2agent.framework.exception.BusinessException error = assertThrows(
                com.rag2agent.framework.exception.BusinessException.class, () -> service.getRun(404L));

        assertEquals("404", error.errorCode().code());
    }

    @Test
    void queuedRunCanBeCancelled() {
        FakeRepository repository = new FakeRepository();
        List<Runnable> tasks = new ArrayList<>();
        EvaluationService service = service(repository, tasks::add);

        RunSubmission submission = service.submit(1L, "cancel", config(3600), "key-cancel");
        service.cancel(submission.runId());
        tasks.getFirst().run();

        assertEquals("CANCELLED", repository.status);
        assertTrue(repository.results.isEmpty());
    }

    private static EvaluationService service(FakeRepository repository, Executor executor) {
        HybridSearchService search = new HybridSearchService(null, null, null, new SimpleMeterRegistry(),
                null, null, null, null) {
            @Override
            public List<com.rag2agent.rag.core.retrieval.RetrievalResult> search(
                    Long kbId, String query, com.rag2agent.bootstrap.service.SearchOptions options) {
                return List.of();
            }
        };
        ChatModelClient chat = new ChatModelClient() {
            @Override
            public com.rag2agent.infra.ai.model.ChatCompletionResponse complete(
                    com.rag2agent.infra.ai.model.ChatCompletionRequest request) {
                return null;
            }

            @Override
            public void stream(com.rag2agent.infra.ai.model.ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {}
        };
        return new EvaluationService(repository, search, chat, new ObjectMapper(),
                new SimpleMeterRegistry(), executor);
    }

    private static EvaluationConfig config(int timeoutSeconds) {
        return new EvaluationConfig(null, 5, 20, 60.0, false, null, false, timeoutSeconds);
    }

    private static class FakeRepository extends EvaluationRepository {
        private final AtomicLong createdCount = new AtomicLong();
        private final AtomicLong id = new AtomicLong(7);
        private final List<CaseResult> results = new CopyOnWriteArrayList<>();
        private final List<EvalCase> cases = List.of(
                new EvalCase(11L, 1L, "question", "answer", List.of(99L)));
        private String status = "QUEUED";
        private String configJson;
        private boolean invalidConfig;
        private String fingerprint;
        private String errorMessage;
        private Instant startedAt = Instant.now();
        private long runId = 7;

        protected FakeRepository() {
            super(null);
        }

        @Override
        public List<EvalCase> listCases(Long kbId) {
            return cases;
        }

        @Override
        public synchronized CreatedRun createRun(Long kbId, String name, String configJson, List<Long> caseIds,
                String idempotencyKey, String requestFingerprint) {
            if (this.fingerprint != null && idempotencyKey != null) {
                return new CreatedRun(runId, false, this.fingerprint);
            }
            this.configJson = this.invalidConfig ? "not-json" : configJson;
            this.fingerprint = requestFingerprint;
            this.runId = id.getAndIncrement();
            createdCount.incrementAndGet();
            return new CreatedRun(runId, true, requestFingerprint);
        }

        @Override
        public synchronized java.util.Optional<EvalRun> findRun(long runId) {
            return java.util.Optional.of(new EvalRun(runId, 1L, "run", status,
                    configJson == null ? "{}" : configJson, cases.size(), results.size(),
                    null, null, null, null, startedAt, null, errorMessage, fingerprint));
        }

        @Override
        public synchronized boolean markRunning(long runId) {
            if (!"QUEUED".equals(status)) return false;
            status = "RUNNING";
            return true;
        }

        @Override
        public boolean isRunning(long runId) {
            return "RUNNING".equals(status);
        }

        @Override
        public List<EvalCase> listRunCases(long runId) {
            return cases;
        }

        @Override
        public Set<Long> listCompletedCaseIds(long runId) {
            return Set.of();
        }

        @Override
        public void insertResult(long runId, CaseResult result) {
            results.add(result);
        }

        @Override
        public List<CaseResult> listResults(long runId) {
            return List.copyOf(results);
        }

        @Override
        public void completeRun(long runId, String status, int totalCases, double hitAtK, double mrr,
                Double faithfulness, Double answerCorrectness, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }

        @Override
        public void failRun(long runId, String errorMessage) {
            status = "FAILED";
            this.errorMessage = errorMessage;
        }

        @Override
        public boolean timeoutRun(long runId) {
            status = "TIMEOUT";
            errorMessage = "评测执行超时";
            return true;
        }

        @Override
        public boolean cancelRun(long runId) {
            status = "CANCELLED";
            return true;
        }
    }

    private static final class MissingRunRepository extends FakeRepository {
        private MissingRunRepository() {
            super();
        }

        @Override
        public java.util.Optional<EvalRun> findRun(long runId) {
            return java.util.Optional.empty();
        }
    }
}
