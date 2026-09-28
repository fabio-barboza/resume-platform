package dev.resumeplatform.resumeai.core.gateway;

import java.io.IOException;
import java.util.List;

public interface PdfGateway {
    List<String> readPages(byte[] content) throws IOException;
}
