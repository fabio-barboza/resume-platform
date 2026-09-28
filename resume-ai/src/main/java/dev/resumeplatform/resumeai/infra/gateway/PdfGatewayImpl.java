package dev.resumeplatform.resumeai.infra.gateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.gateway.PdfGateway;

@Component
public class PdfGatewayImpl implements PdfGateway {
    @Override
    public List<String> readPages(byte[] content) throws IOException {
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
}
