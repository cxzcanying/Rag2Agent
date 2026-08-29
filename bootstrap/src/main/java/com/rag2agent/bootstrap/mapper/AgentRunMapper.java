package com.rag2agent.bootstrap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag2agent.bootstrap.entity.AgentRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Select("SELECT * FROM agent_run WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}")
    AgentRun selectByClientRequest(
            @Param("userId") Long userId, @Param("clientRequestId") String clientRequestId);
}
