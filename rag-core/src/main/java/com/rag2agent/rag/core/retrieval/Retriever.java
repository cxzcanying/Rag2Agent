package com.rag2agent.rag.core.retrieval;

import java.util.List;

public interface Retriever {

    List<RetrievalResult> retrieve(RetrievalQuery query);
}
