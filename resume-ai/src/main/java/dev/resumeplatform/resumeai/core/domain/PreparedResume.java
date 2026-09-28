package dev.resumeplatform.resumeai.core.domain;

import java.util.List;

import dev.resumeplatform.resumeai.core.domain.chunking.TextChunk;

public record PreparedResume(List<String> pages, List<TextChunk> chunks) {
}
