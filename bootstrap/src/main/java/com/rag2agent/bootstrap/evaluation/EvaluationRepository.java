package com.rag2agent.bootstrap.evaluation;

import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public long createRun(Long kbId, String name, String configJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO eval_run (kb_id, name, status, config) VALUES (?, ?, 'RUNNING', CAST(? AS jsonb))",
                    new String[] {"id"});
            statement.setLong(1, kbId);
            statement.setString(2, name);
            statement.setString(3, configJson);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建评测运行后未返回 ID");
        }
        return key.longValue();
    }

    public void insertResult(long runId, CaseResult result) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO eval_case_result
                        (run_id, case_id, first_relevant_rank, reciprocal_rank, returned_doc_ids,
                         generated_answer, faithfulness, answer_correctness, latency_ms, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                SELECT id, kb_id, name, status, total_cases, hit_at_k, mrr, faithfulness,
                       answer_correctness, started_at, completed_at, error_message
                FROM eval_run WHERE kb_id = ? ORDER BY id DESC LIMIT 100
                """,
                (rs, rowNum) -> new RunSummary(
                        rs.getLong("id"),
                        rs.getLong("kb_id"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("total_cases"),
                        nullableDouble(rs.getObject("hit_at_k")),
                        nullableDouble(rs.getObject("mrr")),
                        nullableDouble(rs.getObject("faithfulness")),
                        nullableDouble(rs.getObject("answer_correctness")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("error_message")),
                kbId);
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
}
