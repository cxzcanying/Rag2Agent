package com.rag2agent.rag.core.split;

import com.rag2agent.rag.core.document.ParsedDocument;
import java.util.List;

public interface TextSplitter {

    List<TextChunk> split(ParsedDocument document);
}
