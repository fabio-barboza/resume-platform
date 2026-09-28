package dev.resumeplatform.resumeai.core.domain;

import java.util.List;

public record ResumePage(long total, List<Resume> items) {
}
