package dev.resumeplatform.resumeai.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class PdfFixtures {
    public static final Path SAMPLES_DIR = Path.of("..", "resumes_samples");

    private PdfFixtures() {
    }

    public static byte[] pdf(String text) {
        return multipage(text);
    }

    public static byte[] multipage(String... texts) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 712);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static byte[] withoutText() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static List<Path> samples() {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(SAMPLES_DIR)) {
            return files.filter(p -> p.getFileName().toString().startsWith("curriculo_"))
                    .filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
