package com.rag2agent.bootstrap.evaluation;

import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insertCases(Long kbId, List<CaseInput> cases) {
        int inserted = 0;
        for (CaseInput evalCase : cases) {
            inserted += jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO eval_case (kb_id, question, expected_answer, golden_doc_ids) VALUES (?, ?, ?, ?)");
                statement.setLong(1, kbId);
                statement.setString(2, evalCase.question());
                statement.setString(3, evalCase.expectedAnswer());
                statement.setArray(4, connection.createArrayOf("bigint", evalCase.goldenDocumentIds().toArray()));
                return statement;
            });
        }
        return inserted;
    }

    public List<EvalCase> listCases(Long kbId) {
        return jdbc.query(
                "SELECT id, kb_id, question, expected_answer, golden_doc_ids FROM eval_case WHERE kb_id = ? ORDER BY id",
                (rs, rowNum) -> new EvalCase(
                        rs.getLong("id"),
                        rs.getLong("kb_id"),
                        rs.getString("question"),
                        rs.getString("expected_answer"),
                        toLongList(rs.getArray("golden_doc_ids"))),
                kbId);
    }

    public CreatedRun createRun(
            Long kbId, String name, String configJson, List<Long> caseIds,
            String idempotencyKey, String requestFingerprint) {
        int totalCases = caseIds.size();
        if (idempotencyKey != null) {
            List<Long> insertedIds = jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO eval_run
                            (kb_id, name, status, config, total_cases, case_ids,
                             idempotency_key, request_fingerprint)
                        VALUES (?, ?, 'QUEUED', CAST(? AS jsonb), ?, ?, ?, ?)
                        ON CONFLICT (kb_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                        RETURNING id
                        """);
                statement.setLong(1, kbId);
                statement.setString(2, name);
                statement.setString(3, configJson);
                statement.setInt(4, totalCases);
                statement.setArray(5, connection.createArrayOf("bigint", caseIds.toArray()));
                statement.setString(6, idempotencyKey);
                statement.setString(7, requestFingerprint);
                return statement;
            }, (rs, rowNum) -> rs.getLong("id"));
            if (!insertedIds.isEmpty()) {
                return new CreatedRun(insertedIds.getFirst(), true, requestFingerprint);
            }
            return jdbc.queryForObject(
                    "SELECT id, request_fingerprint FROM eval_run WHERE kb_id = ? AND idempotency_key = ?",
                    (rs, rowNum) -> new CreatedRun(
                            rs.getLong("id"), false, rs.getString("request_fingerprint")),
                    kbId, idempotencyKey);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO eval_run
                        (kb_id, name, status, config, total_cases, case_ids, request_fingerprint)
                    VALUES (?, ?, 'QUEUED', CAST(? AS jsonb), ?, ?, ?)
                    """,
                    new String[] {"id"});
            statement.setLong(1, kbId);
            statement.setString(2, name);
            statement.setString(3, configJson);
            statement.setInt(4, totalCases);
            statement.setArray(5, connection.createArrayOf("bigint", caseIds.toArray()));
            statement.setString(6, requestFingerprint);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建评测运行后未返回 ID");
        }
        return new CreatedRun(key.longValue(), true, requestFingerprint);
    }

    public void insertResult(long runId, CaseResult result) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO eval_case_result
                        (run_id, case_id, first_relevant_rank, reciprocal_rank, returned_doc_ids,
                         generated_answer, faithfulness, answer_correctness, latency_ms, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, case_id) DO UPDATE SET
                        first_relevant_rank = EXCLUDED.first_relevant_rank,
                        reciprocal_rank = EXCLUDED.reciprocal_rank,
                        returned_doc_ids = EXCLUDED.returned_doc_ids,
                        generated_answer = EXCLUDED.generated_answer,
                        faithfulness = EXCLUDED.faithfulness,
                        answer_correctness = EXCLUDED.answer_correctness,
                        latency_ms = EXCLUDED.latency_ms,
                        error_message = EXCLUDED.error_message
                    """);
            statement.setLong(1, runId);
            statement.setLong(2, result.caseId());
            statement.setInt(3, result.firstRelevantRank());
            statement.setDouble(4, result.reciprocalRank());
            statement.setArray(5, connection.createArrayOf("bigint", result.returnedDocumentIds().toArray()));
            statement.setString(6, result.generatedAnswer());
            setNullableDouble(statement, 7, result.faithfulness());
            setNullableDouble(statement, 8, result.answerCorrectness());
            statement.setInt(9, result.latencyMs());
            statement.setString(10, result.errorMessage());
            return statement;
        });
    }

    public boolean markRunning(long runId) {
        return jdbc.update(
                "UPDATE eval_run SET status = 'RUNNING', completed_at = NULL, error_message = NULL "
                        + "WHERE id = ? AND status = 'QUEUED'",
                runId) == 1;
    }

    public int requeueInterruptedRuns() {
        return jdbc.update("UPDATE eval_run SET status = 'QUEUED' WHERE status = 'RUNNING'");
    }

    public List<Long> listQueuedRunIds() {
        return jdbc.query(
                "SELECT id FROM eval_run WHERE status = 'QUEUED' ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"));
    }

    public List<EvalCase> listRunCases(long runId) {
        return jdbc.query(
                """
                SELECT c.id, c.kb_id, c.question, c.expected_answer, c.golden_doc_ids
                FROM eval_run r
                JOIN eval_case c ON c.id = ANY(r.case_ids)
                WHERE r.id = ? ORDER BY c.id
                """,
                (rs, rowNum) -> new EvalCase(
                        rs.getLong("id"),
                        rs.getLong("kb_id"),
                        rs.getString("question"),
                        rs.getString("expected_answer"),
                        toLongList(rs.getArray("golden_doc_ids"))),
                runId);
    }

    public Set<Long> listCompletedCaseIds(long runId) {
        return new HashSet<>(jdbc.query(
                "SELECT case_id FROM eval_case_result WHERE run_id = ?",
                (rs, rowNum) -> rs.getLong("case_id"), runId));
    }

    public List<CaseResult> listResults(long runId) {
        return jdbc.query(
                """
                SELECT r.case_id, c.question, r.first_relevant_rank, r.reciprocal_rank,
                       r.returned_doc_ids, r.generated_answer, r.faithfulness,
                       r.answer_correctness, r.latency_ms, r.error_message
                FROM eval_case_result r
                JOIN eval_case c ON c.id = r.case_id
                WHERE r.run_id = ? ORDER BY r.id
                """,
                (rs, rowNum) -> new CaseResult(
                        rs.getLong("case_id"),
                        rs.getString("question"),
                        rs.getInt("first_relevant_rank"),
                        rs.getDouble("reciprocal_rank"),
                        toLongList(rs.getArray("returned_doc_ids")),
                        rs.getString("generated_answer"),
                        nullableDouble(rs.getObject("faithfulness")),
                        nullableDouble(rs.getObject("answer_correctness")),
                        rs.getInt("latency_ms"),
                        rs.getString("error_message")),
                runId);
    }

    public Optional<EvalRun> findRun(long runId) {
        List<EvalRun> runs = jdbc.query(
                """
                SELECT r.id, r.kb_id, r.name, r.status, r.config::text, r.total_cases,
                       (SELECT count(*) FROM eval_case_result cr WHERE cr.run_id = r.id) AS completed_cases,
                       r.hit_at_k, r.mrr, r.faithfulness, r.answer_correctness,
                       r.started_at, r.completed_at, r.error_message, r.request_fingerprint
                FROM eval_run r WHERE r.id = ?
                """,
                (rs, rowNum) -> mapRun(rs), runId);
        return runs.stream().findFirst();
    }

    public void failRun(long runId, String errorMessage) {
        jdbc.update(
                "UPDATE eval_run SET status = 'FAILED', error_message = ?, completed_at = now() WHERE id = ?",
                errorMessage, runId);
    }

    public void completeRun(
            long runId, String status, int totalCases, double hitAtK, double mrr,
            Double faithfulness, Double answerCorrectness, String errorMessage) {
        jdbc.update(
                """
                UPDATE eval_run
                SET status = ?, total_cases = ?, hit_at_k = ?, mrr = ?, faithfulness = ?,
                    answer_correctness = ?, error_message = ?, completed_at = now()
                WHERE id = ?
                """,
                status, totalCases, hitAtK, mrr, faithfulness, answerCorrectness, errorMessage, runId);
    }

    public List<RunSummary> listRuns(Long kbId) {
        return jdbc.query(
                """
                SELECT r.id, r.kb_id, r.name, r.status, r.total_cases,
                       (SELECT count(*) FROM eval_case_result cr WHERE cr.run_id = r.id) AS completed_cases,
                       r.hit_at_k, r.mrr, r.faithfulness, r.answer_correctness,
                       r.started_at, r.completed_at, r.error_message
                FROM eval_run r WHERE r.kb_id = ? ORDER BY r.id DESC LIMIT 100
                """,
                (rs, rowNum) -> new RunSummary(
                        rs.getLong("id"),
                        rs.getLong("kb_id"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("total_cases"),
                        rs.getInt("completed_cases"),
                        nullableDouble(rs.getObject("hit_at_k")),
                        nullableDouble(rs.getObject("mrr")),
                        nullableDouble(rs.getObject("faithfulness")),
                        nullableDouble(rs.getObject("answer_correctness")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("error_message")),
                kbId);
    }

    private static EvalRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvalRun(
                rs.getLong("id"),
                rs.getLong("kb_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("config"),
                rs.getInt("total_cases"),
                rs.getInt("completed_cases"),
                nullableDouble(rs.getObject("hit_at_k")),
                nullableDouble(rs.getObject("mrr")),
                nullableDouble(rs.getObject("faithfulness")),
                nullableDouble(rs.getObject("answer_correctness")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                rs.getString("error_message"),
                rs.getString("request_fingerprint"));
    }

    private static List<Long> toLongList(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object[] values = (Object[]) sqlArray.getArray();
        List<Long> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(((Number) value).longValue());
        }
        return List.copyOf(result);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record EvalCase(
            Long id, Long kbId, String question, String expectedAnswer, List<Long> goldenDocumentIds) {}

    public record CreatedRun(long runId, boolean created, String requestFingerprint) {}

    public record EvalRun(
            Long id,
            Long kbId,
            String name,
            String status,
            String configJson,
            int totalCases,
            int completedCases,
            Double hitAtK,
            Double mrr,
            Double faithfulness,
            Double answerCorrectness,
            Instant startedAt,
            Instant completedAt,
            String errorMessage,
            String requestFingerprint) {}
}
