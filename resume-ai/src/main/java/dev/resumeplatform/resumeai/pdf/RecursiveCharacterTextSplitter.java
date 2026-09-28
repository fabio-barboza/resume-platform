package dev.resumeplatform.resumeai.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecursiveCharacterTextSplitter {
    private static final List<String> SEPARATORS = List.of("\n\n", "\n", " ", "");

    private final int chunkSize;
    private final int chunkOverlap;

    public RecursiveCharacterTextSplitter(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<String> splitText(String text) {
        return split(text, SEPARATORS);
    }

    private List<String> split(String text, List<String> separators) {
        List<String> finalChunks = new ArrayList<>();
        String separator = separators.getLast();
        List<String> newSeparators = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String candidate = separators.get(i);
            if (candidate.isEmpty()) {
                separator = candidate;
                break;
            }
            if (text.contains(candidate)) {
                separator = candidate;
                newSeparators = separators.subList(i + 1, separators.size());
                break;
            }
        }

        List<String> splits = splitKeepingSeparator(text, separator);
        List<String> goodSplits = new ArrayList<>();

        String mergeSeparator = "";
        for (String piece : splits) {
            if (length(piece) < chunkSize) {
                goodSplits.add(piece);
                continue;
            }
            if (!goodSplits.isEmpty()) {
                finalChunks.addAll(mergeSplits(goodSplits, mergeSeparator));
                goodSplits = new ArrayList<>();
            }
            if (newSeparators.isEmpty()) {
                finalChunks.add(piece);
            } else {
                finalChunks.addAll(split(piece, newSeparators));
            }
        }
        if (!goodSplits.isEmpty()) {
            finalChunks.addAll(mergeSplits(goodSplits, mergeSeparator));
        }
        return finalChunks;
    }

    private static List<String> splitKeepingSeparator(String text, String separator) {
        List<String> result = new ArrayList<>();
        if (separator.isEmpty()) {
            text.codePoints().forEach(cp -> result.add(new String(Character.toChars(cp))));
            return result;
        }
        Matcher matcher = Pattern.compile(Pattern.quote(separator)).matcher(text);
        int last = 0;
        String pendingSeparator = "";
        while (matcher.find()) {
            String piece = pendingSeparator + text.substring(last, matcher.start());
            if (last == 0 && pendingSeparator.isEmpty()) {
                piece = text.substring(0, matcher.start());
            }
            result.add(piece);
            pendingSeparator = matcher.group();
            last = matcher.end();
        }
        result.add(pendingSeparator + text.substring(last));
        result.removeIf(String::isEmpty);
        return result;
    }

    private List<String> mergeSplits(List<String> splits, String separator) {
        int separatorLength = length(separator);
        List<String> docs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int total = 0;
        for (String piece : splits) {
            int pieceLength = length(piece);
            if (total + pieceLength + (current.isEmpty() ? 0 : separatorLength) > chunkSize) {
                if (!current.isEmpty()) {
                    String doc = join(current, separator);
                    if (doc != null) {
                        docs.add(doc);
                    }
                    while (total > chunkOverlap
                            || (total + pieceLength + (current.isEmpty() ? 0 : separatorLength) > chunkSize
                                    && total > 0)) {
                        total -= length(current.getFirst()) + (current.size() > 1 ? separatorLength : 0);
                        current.removeFirst();
                    }
                }
            }
            current.add(piece);
            total += pieceLength + (current.size() > 1 ? separatorLength : 0);
        }
        String doc = join(current, separator);
        if (doc != null) {
            docs.add(doc);
        }
        return docs;
    }

    private static String join(List<String> pieces, String separator) {
        String text = String.join(separator, pieces).strip();
        return text.isEmpty() ? null : text;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
