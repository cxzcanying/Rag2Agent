package com.rag2agent.bootstrap.dto;

import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;

public final class SearchDtos {

    private SearchDtos() {}

    public record MultimodalSearchResponse(
            String effectiveQuery,
            boolean imageUsed,
            List<RetrievalResult> results) {}
}
