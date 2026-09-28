package dev.resumeplatform.resumeai.pdf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfText {
    public static final int CHUNK_SIZE = 2000;
    public static final int CHUNK_OVERLAP = 500;

    private static final RecursiveCharacterTextSplitter SPLITTER =
            new RecursiveCharacterTextSplitter(CHUNK_SIZE, CHUNK_OVERLAP);

    private PdfText() {
    }

    public static String fileHash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static List<String> readPages(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pages.add(text == null ? "" : text.stripTrailing());
            }
            return pages;
        }
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
