package com.rag2agent.rag.core.retrieval;

import java.util.List;

public interface RetrievalPostProcessor {

    List<RetrievalResult> process(RetrievalQuery query, List<RetrievalResult> results);
}
