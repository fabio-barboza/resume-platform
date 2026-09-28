package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;

import dev.resumeplatform.resumeai.infra.repository.projection.ResumeRow;

public interface DocumentRepositoryCustom {
    List<ResumeRow> listRows(int limit, int offset);
}
