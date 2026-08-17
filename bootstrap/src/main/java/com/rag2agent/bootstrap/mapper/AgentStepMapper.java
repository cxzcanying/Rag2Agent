package com.rag2agent.bootstrap.mapper;

import com.rag2agent.bootstrap.entity.AgentStep;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface AgentStepMapper {

    @Insert("""
            INSERT INTO agent_step (run_id, seq, step_type, status, input, output, duration_ms)
            VALUES (#{runId}, #{seq}, #{stepType}, #{status},
                    CAST(#{input,jdbcType=VARCHAR} AS jsonb),
                    CAST(#{output,jdbcType=VARCHAR} AS jsonb),
                    #{durationMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentStep step);

    @Select("SELECT * FROM agent_step WHERE run_id = #{runId} ORDER BY seq")
    List<AgentStep> listByRunId(Long runId);
}
