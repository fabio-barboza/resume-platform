package dev.resumeplatform.resumeai.db;

import java.util.List;

public interface DocumentRepositoryCustom {
    List<ResumeRow> listRows(int limit, int offset);
}
