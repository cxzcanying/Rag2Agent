package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseView;
import com.rag2agent.bootstrap.entity.KnowledgeBase;
import com.rag2agent.bootstrap.mapper.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper) {
        this.kbMapper = kbMapper;
    }

    public KnowledgeBaseView create(Long userId, CreateKnowledgeBaseRequest request) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setOwnerUserId(userId);
        kbMapper.insert(kb);
        return KnowledgeBaseView.from(kb);
    }

    public List<KnowledgeBaseView> list(Long userId) {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getOwnerUserId, userId)
                        .orderByDesc(KnowledgeBase::getId))
                .stream().map(KnowledgeBaseView::from).toList();
    }

    public void requireOwned(Long userId, Long kbId) {
        if (userId == null || kbId == null || kbId <= 0
                || kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kbId)
                        .eq(KnowledgeBase::getOwnerUserId, userId)) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }
}
