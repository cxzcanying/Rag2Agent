package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseView;
import com.rag2agent.bootstrap.entity.KnowledgeBase;
import com.rag2agent.bootstrap.mapper.KnowledgeBaseMapper;
import java.util.List;
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

    public List<KnowledgeBaseView> list() {
        return kbMapper.selectList(null).stream().map(KnowledgeBaseView::from).toList();
    }
}
