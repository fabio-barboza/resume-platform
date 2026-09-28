package dev.resumeplatform.resumeai.infra.support;

public final class LikePatterns {
    private LikePatterns() {
    }

    public static String escape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
