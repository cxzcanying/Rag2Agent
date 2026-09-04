package com.rag2agent.bootstrap.mapper;

import com.rag2agent.bootstrap.entity.ToolCallRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ToolCallRecordMapper {

    @Insert("""
            INSERT INTO tool_call (run_id, step_id, tool_name, status, input, output, error_message)
            VALUES (#{runId}, #{stepId}, #{toolName}, #{status},
                    CAST(#{input,jdbcType=VARCHAR} AS jsonb),
                    CAST(#{output,jdbcType=VARCHAR} AS jsonb),
                    #{errorMessage})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ToolCallRecord record);

    @Select("SELECT * FROM tool_call WHERE id = #{id}")
    ToolCallRecord selectById(Long id);

    @Update("""
            UPDATE tool_call
            SET status = #{status},
                output = CAST(#{output,jdbcType=VARCHAR} AS jsonb),
                error_message = #{errorMessage},
                updated_at = now()
            WHERE id = #{id}
            """)
    int updateResult(ToolCallRecord record);

    @Update("UPDATE tool_call SET status = 'EXECUTING', updated_at = now() "
            + "WHERE id = #{id} AND run_id = #{runId} AND status = 'WAITING_APPROVAL'")
    int claimApproval(@Param("id") Long id, @Param("runId") Long runId);

    /** 审批超时：把某次执行下所有仍处 WAITING_APPROVAL 的工具调用置为 TIMED_OUT。 */
    @Update("UPDATE tool_call SET status = 'TIMED_OUT', error_message = #{message}, updated_at = now() "
            + "WHERE run_id = #{runId} AND status = 'WAITING_APPROVAL'")
    int timeoutPendingApproval(@Param("runId") Long runId, @Param("message") String message);

    @Select("SELECT * FROM tool_call WHERE run_id = #{runId} ORDER BY id")
    List<ToolCallRecord> listByRunId(Long runId);
}
