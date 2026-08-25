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

/**
 * 评测数据访问层（Repository）
 *
 * 负责评测功能的所有数据库操作，涉及三张核心表：
 *
 * 1. eval_case：测试用例表
 *    - 存储问题、参考答案、相关文档ID列表
 *
 * 2. eval_run：评测任务表
 *    - 存储任务元信息、状态、配置、汇总指标
 *    - 关键字段：idempotency_key（幂等键）+ request_fingerprint（请求指纹）
 *
 * 3. eval_case_result：用例评测结果表
 *    - 存储每个用例的详细评测结果
 *    - 包含检索指标、生成答案、裁判打分等
 *
 * @author 21311
 */
@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 测试用例相关操作 ====================

    /**
     * 批量导入测试用例
     *
     * 将一组测试用例插入到 eval_case 表中
     * golden_doc_ids 使用 PostgreSQL 数组类型存储（bigint[]）
     *
     * @param kbId  知识库ID
     * @param cases 测试用例列表
     * @return 实际插入的行数
     */
    public int insertCases(Long kbId, List<CaseInput> cases) {
        int inserted = 0;
        for (CaseInput evalCase : cases) {
            inserted += jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO eval_case (kb_id, question, expected_answer, golden_doc_ids) VALUES (?, ?, ?, ?)");
                statement.setLong(1, kbId);
                statement.setString(2, evalCase.question());
                statement.setString(3, evalCase.expectedAnswer());
                // 将 List<Long> 转换为 PostgreSQL 数组类型
                statement.setArray(4, connection.createArrayOf("bigint", evalCase.goldenDocumentIds().toArray()));
                return statement;
            });
        }
        return inserted;
    }

    /**
     * 查询某知识库下的所有测试用例
     *
     * @param kbId 知识库ID
     * @return 测试用例列表（按ID升序）
     */
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

    // ==================== 评测任务相关操作（核心） ====================

    /**
     * 创建评测任务（核心方法，包含幂等性处理）
     *
     * 幂等性实现机制：
     *
     * 1. 如果提供了 idempotencyKey：
     *    - 使用 INSERT ... ON CONFLICT (kb_id, idempotency_key) 进行"插入或忽略"
     *    - 数据库唯一索引保证同一个 Key 只会插入一次
     *    - 如果插入成功 → 返回新任务（created=true）
     *    - 如果冲突（Key已存在）→ 查询返回已有任务（created=false）
     *
     * 2. 如果未提供 idempotencyKey：
     *    - 直接插入新记录，无幂等性保证
     *
     * @param kbId                知识库ID
     * @param name                任务名称
     * @param configJson          评测配置（JSON格式）
     * @param caseIds             该任务包含的测试用例ID列表
     * @param idempotencyKey      幂等键（客户端生成的UUID），可为null
     * @param requestFingerprint  请求指纹（SHA-256哈希），用于校验同一Key下请求内容是否一致
     * @return 创建结果（runId + 是否为新创建 + 请求指纹）
     */
    public CreatedRun createRun(
            Long kbId, String name, String configJson, List<Long> caseIds,
            String idempotencyKey, String requestFingerprint) {
        int totalCases = caseIds.size();

        // ===== 情况1：提供了幂等键 =====
        if (idempotencyKey != null) {
            // 使用 ON CONFLICT 实现"插入或忽略"语义
            // 唯一约束：(kb_id, idempotency_key) WHERE idempotency_key IS NOT NULL
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

            // 插入成功 → 返回新创建的任务
            if (!insertedIds.isEmpty()) {
                return new CreatedRun(insertedIds.getFirst(), true, requestFingerprint);
            }

            // 插入失败（幂等键冲突）→ 查询返回已有的任务
            return jdbc.queryForObject(
                    "SELECT id, request_fingerprint FROM eval_run WHERE kb_id = ? AND idempotency_key = ?",
                    (rs, rowNum) -> new CreatedRun(
                            rs.getLong("id"), false, rs.getString("request_fingerprint")),
                    kbId, idempotencyKey);
        }

        // ===== 情况2：未提供幂等键（普通插入） =====
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

    /**
     * 保存单个用例的评测结果
     *
     * 使用 ON CONFLICT (run_id, case_id) 支持"插入或更新"
     * 这样可以在断点续跑时重新执行失败的用例并更新结果
     *
     * @param runId  任务ID
     * @param result 用例评测结果
     */
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

    // ==================== 任务状态管理 ====================

    /**
     * 将任务状态从 QUEUED 更新为 RUNNING
     *
     * 使用 CAS（Compare-And-Swap）方式：
     * WHERE id = ? AND status = 'QUEUED'
     *
     * 只有当前状态是 QUEUED 时才会更新，防止重复执行
     * 返回 true 表示更新成功（获得执行权），false 表示任务已被其他线程抢占
     *
     * @param runId 任务ID
     * @return true=成功标记为RUNNING，false=任务不在QUEUED状态
     */
    public boolean markRunning(long runId) {
        return jdbc.update(
                "UPDATE eval_run SET status = 'RUNNING', started_at = now(), completed_at = NULL, error_message = NULL "
                        + "WHERE id = ? AND status = 'QUEUED'",
                runId) == 1;
    }

    /**
     * 将状态为 RUNNING 的任务重新置为 QUEUED
     *
     * 用于应用重启时的恢复：
     * 应用突然崩溃，RUNNING 状态的任务实际上没有完成
     * 需要将它们重新放回队列，等待重新执行
     *
     * @return 受影响的行数
     */
    public int requeueInterruptedRuns() {
        return jdbc.update("UPDATE eval_run SET status = 'QUEUED' WHERE status = 'RUNNING'");
    }

    /**
     * 查询所有 QUEUED 状态的任务ID
     *
     * @return 任务ID列表（按ID升序）
     */
    public List<Long> listQueuedRunIds() {
        return jdbc.query(
                "SELECT id FROM eval_run WHERE status = 'QUEUED' ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"));
    }

    /**
     * 标记任务为失败
     *
     * @param runId         任务ID
     * @param errorMessage  错误信息
     */
    public void failRun(long runId, String errorMessage) {
        jdbc.update(
                "UPDATE eval_run SET status = 'FAILED', error_message = ?, completed_at = now() WHERE id = ?",
                errorMessage, runId);
    }

    public boolean cancelRun(long runId) {
        return jdbc.update(
                "UPDATE eval_run SET status = 'CANCELLED', error_message = ?, completed_at = now() "
                        + "WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                "评测已取消", runId) == 1;
    }

    public boolean timeoutRun(long runId) {
        return jdbc.update(
                "UPDATE eval_run SET status = 'TIMEOUT', error_message = ?, completed_at = now() "
                        + "WHERE id = ? AND status = 'RUNNING'",
                "评测执行超时", runId) == 1;
    }

    public boolean isRunning(long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM eval_run WHERE id = ? AND status = 'RUNNING'", Integer.class, runId);
        return count != null && count == 1;
    }

    /**
     * 标记任务为完成，保存所有汇总指标
     *
     * @param runId               任务ID
     * @param status              最终状态（COMPLETED 或 COMPLETED_WITH_ERRORS）
     * @param totalCases          总用例数
     * @param hitAtK              Hit@K 指标
     * @param mrr                 MRR 指标
     * @param faithfulness        平均忠实度
     * @param answerCorrectness   平均答案正确性
     * @param errorMessage        错误信息（如果有）
     */
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

    // ==================== 查询操作 ====================

    /**
     * 查询某任务关联的所有测试用例
     *
     * 通过 eval_run 表中的 case_ids 数组关联到 eval_case 表
     *
     * @param runId 任务ID
     * @return 测试用例列表
     */
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

    /**
     * 查询某任务已完成的用例ID集合
     *
     * 用于断点续跑：跳过已经执行过的用例
     *
     * @param runId 任务ID
     * @return 已完成用例ID的Set
     */
    public Set<Long> listCompletedCaseIds(long runId) {
        return new HashSet<>(jdbc.query(
                "SELECT case_id FROM eval_case_result WHERE run_id = ?",
                (rs, rowNum) -> rs.getLong("case_id"), runId));
    }

    /**
     * 查询某任务的所有用例评测结果
     *
     * @param runId 任务ID
     * @return 用例结果列表
     */
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

    /**
     * 查询单个任务的完整信息
     *
     * @param runId 任务ID
     * @return 任务信息（Optional包装）
     */
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

    /**
     * 查询某知识库下的所有任务摘要列表
     *
     * 限制返回最近100条，按ID降序排列
     *
     * @param kbId 知识库ID
     * @return 任务摘要列表
     */
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

    // ==================== 私有辅助方法 ====================

    /**
     * 将 ResultSet 映射为 EvalRun 对象
     */
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

    /**
     * 将 PostgreSQL 数组转换为 List<Long>
     *
     * @param sqlArray PostgreSQL 数组对象
     * @return List<Long>（不可变）
     */
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

    /**
     * 设置可空的 Double 参数
     */
    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    /**
     * 将 Object 安全转换为 Double（用于数据库查询结果）
     */
    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    /**
     * 将 Timestamp 转换为 Instant
     */
    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    // ==================== 内部记录类（Record） ====================

    /**
     * 测试用例内部表示
     *
     * @param id                 用例ID
     * @param kbId               所属知识库ID
     * @param question           问题
     * @param expectedAnswer     期望答案（参考答案）
     * @param goldenDocumentIds  相关文档ID列表（人工标注）
     */
    public record EvalCase(
            Long id, Long kbId, String question, String expectedAnswer, List<Long> goldenDocumentIds) {}

    /**
     * 创建任务的结果
     *
     * @param runId               任务ID
     * @param created             是否为新创建（true=新建，false=幂等返回已有）
     * @param requestFingerprint  请求指纹
     */
    public record CreatedRun(long runId, boolean created, String requestFingerprint) {}

    /**
     * 评测任务完整信息
     */
    public record EvalRun(
            Long id,
            Long kbId,
            String name,
            String status,                // QUEUED | RUNNING | COMPLETED | COMPLETED_WITH_ERRORS | FAILED | CANCELLED | TIMEOUT
            String configJson,            // 评测配置（JSON）
            int totalCases,               // 总用例数
            int completedCases,           // 已完成用例数
            Double hitAtK,                // Hit@K 指标
            Double mrr,                   // MRR 指标
            Double faithfulness,          // 平均忠实度
            Double answerCorrectness,     // 平均答案正确性
            Instant startedAt,            // 开始时间
            Instant completedAt,          // 完成时间
            String errorMessage,          // 错误信息
            String requestFingerprint) {} // 请求指纹
}
