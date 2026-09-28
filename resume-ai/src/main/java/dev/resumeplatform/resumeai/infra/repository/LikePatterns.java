package dev.resumeplatform.resumeai.infra.repository;

final class LikePatterns {
    private LikePatterns() {
    }

    static String escape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
