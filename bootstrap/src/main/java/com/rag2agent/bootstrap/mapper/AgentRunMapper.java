package com.rag2agent.bootstrap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag2agent.bootstrap.entity.AgentRun;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Select("SELECT * FROM agent_run WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}")
    AgentRun selectByClientRequest(
            @Param("userId") Long userId, @Param("clientRequestId") String clientRequestId);

    /** 找出审批挂起超过 thresholdSeconds 的 Agent 执行，供后台自动终态化。 */
    @Select("SELECT * FROM agent_run WHERE status = 'WAITING_APPROVAL' "
            + "AND updated_at < now() - make_interval(secs => #{seconds})")
    List<AgentRun> listStaleWaitingApproval(@Param("seconds") long seconds);

    /** CAS 终态化：只有仍处于 WAITING_APPROVAL 才允许置为 FAILED，避免覆盖并发审批结果。 */
    @Update("UPDATE agent_run SET status = 'FAILED', answer = #{answer} "
            + "WHERE id = #{id} AND status = 'WAITING_APPROVAL'")
    int markApprovalTimeout(@Param("id") Long id, @Param("answer") String answer);
}
