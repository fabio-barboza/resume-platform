package dev.resumeplatform.resumeai.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class RecursiveCharacterTextSplitterTest {
    record GoldenCase(String text, List<String> chunks) {
    }

    @Test
    void matchesLangchainOutputExactly() throws IOException {
        List<GoldenCase> cases;
        try (InputStream in = getClass().getResourceAsStream("/splitter/langchain_golden.json")) {
            cases = JsonMapper.builder().build().readValue(in, new TypeReference<List<GoldenCase>>() {
            });
        }
        var splitter = new RecursiveCharacterTextSplitter(PdfText.CHUNK_SIZE, PdfText.CHUNK_OVERLAP);

        assertThat(cases).isNotEmpty();
        for (int i = 0; i < cases.size(); i++) {
            assertThat(splitter.splitText(cases.get(i).text())).as("caso %d", i).isEqualTo(cases.get(i).chunks());
        }
    }

    @Test
    void chunkIndexRestartsPerPageAndBlankPagesAreSkipped() {
        var chunks = PdfText.buildChunks(List.of("primeira página", "   ", "terceira página"));

        assertThat(chunks).containsExactly(new TextChunk(0, 0, "primeira página"), new TextChunk(2, 0, "terceira página"));
        assertThat(chunks.getLast().idFor(42)).isEqualTo("42-p2-c0");
    }

    @Test
    void fileHashIsSha256Hex() {
        assertThat(PdfText.fileHash("abc".getBytes()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
