package dev.resumeplatform.resumeai.core.domain.text;

import java.text.Normalizer;

public final class TextFolding {
    private TextFolding() {
    }

    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(cp -> !isCombining(cp))
                .forEach(folded::appendCodePoint);
        return folded.toString();
    }

    public static boolean isCombining(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
