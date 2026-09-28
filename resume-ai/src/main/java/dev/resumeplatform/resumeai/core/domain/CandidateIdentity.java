package dev.resumeplatform.resumeai.core.domain;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CandidateIdentity(String name, String email, String phone) {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern PHONE =
            Pattern.compile("(?:\\+55[\\s.-]?)?(?:\\(?\\d{2}\\)?[\\s.-]?)?\\d{4,5}[\\s.-]?\\d{4}");
    private static final Set<String> NULL_LITERALS = Set.of("null", "none", "n/a", "-");

    public static CandidateIdentity empty() {
        return new CandidateIdentity(null, null, null);
    }

    public boolean hasIdentity() {
        return name != null || email != null || phone != null;
    }

    public ResumeStatus status() {
        return email != null ? ResumeStatus.INGESTED : ResumeStatus.PENDING_REVIEW;
    }

    public CandidateIdentity completedFrom(String text) {
        String cleanName = normalize(name);
        String cleanEmail = orElse(normalize(email), firstMatch(EMAIL, text));
        String cleanPhone = orElse(normalize(phone), firstMatch(PHONE, text));
        return new CandidateIdentity(cleanName, cleanEmail, cleanPhone);
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || NULL_LITERALS.contains(cleaned.toLowerCase())) {
            return null;
        }
        return cleaned;
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
