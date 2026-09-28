package dev.resumeplatform.resumeai.core.domain.chunking;

import java.util.ArrayList;
import java.util.List;

public final class ResumeChunker {
    public static final int CHUNK_SIZE = 2000;
    public static final int CHUNK_OVERLAP = 500;

    private static final RecursiveCharacterTextSplitter SPLITTER =
            new RecursiveCharacterTextSplitter(CHUNK_SIZE, CHUNK_OVERLAP);

    private ResumeChunker() {
    }

    public static String firstPagesText(List<String> pages, int limit) {
        return String.join("\n\n", pages.subList(0, Math.min(limit, pages.size()))).strip();
    }

    public static List<TextChunk> buildChunks(List<String> pages) {
        List<TextChunk> chunks = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            String text = pages.get(page);
            if (text.isBlank()) {
                continue;
            }
            int index = 0;
            for (String piece : SPLITTER.splitText(text)) {
                chunks.add(new TextChunk(page, index++, piece));
            }
        }
        return chunks;
    }
}
