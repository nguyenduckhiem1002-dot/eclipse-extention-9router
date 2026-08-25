package com.casla.eclipse.ai.learning;

import java.util.Properties;

/** Aggregate counters only; no source code or completion text is persisted. */
public final class CompletionFeedbackStats {
    private long generated;
    private long acceptedFull;
    private long acceptedWord;
    private long acceptedLine;
    private long typedMatch;
    private long dismissed;
    private long superseded;
    private long editedAfterAccept;

    public void generated() { generated++; }
    public void acceptedFull() { acceptedFull++; }
    public void acceptedWord() { acceptedWord++; }
    public void acceptedLine() { acceptedLine++; }
    public void typedMatch() { typedMatch++; }
    public void dismissed() { dismissed++; }
    public void superseded() { superseded++; }
    public void editedAfterAccept() { editedAfterAccept++; }

    public long generatedCount() { return generated; }
    public long acceptedFullCount() { return acceptedFull; }
    public long acceptedWordCount() { return acceptedWord; }
    public long acceptedLineCount() { return acceptedLine; }
    public long typedMatchCount() { return typedMatch; }
    public long dismissedCount() { return dismissed; }
    public long supersededCount() { return superseded; }
    public long editedAfterAcceptCount() { return editedAfterAccept; }

    public long acceptedCount() {
        return acceptedFull + acceptedWord + acceptedLine + typedMatch;
    }

    public double acceptanceRate() {
        return generated == 0 ? 0.0 : (double) acceptedCount() / generated;
    }

    public double editAfterAcceptRate() {
        long accepted = acceptedFull + acceptedWord + acceptedLine;
        return accepted == 0 ? 0.0 : (double) editedAfterAccept / accepted;
    }

    public void reset() {
        generated = acceptedFull = acceptedWord = acceptedLine = typedMatch = 0;
        dismissed = superseded = editedAfterAccept = 0;
    }

    public void store(Properties properties, String prefix) {
        put(properties, prefix + "generated", generated);
        put(properties, prefix + "acceptedFull", acceptedFull);
        put(properties, prefix + "acceptedWord", acceptedWord);
        put(properties, prefix + "acceptedLine", acceptedLine);
        put(properties, prefix + "typedMatch", typedMatch);
        put(properties, prefix + "dismissed", dismissed);
        put(properties, prefix + "superseded", superseded);
        put(properties, prefix + "editedAfterAccept", editedAfterAccept);
    }

    public void load(Properties properties, String prefix) {
        generated = read(properties, prefix + "generated");
        acceptedFull = read(properties, prefix + "acceptedFull");
        acceptedWord = read(properties, prefix + "acceptedWord");
        acceptedLine = read(properties, prefix + "acceptedLine");
        typedMatch = read(properties, prefix + "typedMatch");
        dismissed = read(properties, prefix + "dismissed");
        superseded = read(properties, prefix + "superseded");
        editedAfterAccept = read(properties, prefix + "editedAfterAccept");
    }

    private static void put(Properties properties, String key, long value) {
        properties.setProperty(key, Long.toString(value));
    }

    private static long read(Properties properties, String key) {
        try {
            return Math.max(0L, Long.parseLong(properties.getProperty(key, "0")));
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }
}
