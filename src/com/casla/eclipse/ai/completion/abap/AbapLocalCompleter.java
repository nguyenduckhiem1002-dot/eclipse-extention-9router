package com.casla.eclipse.ai.completion.abap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

/**
 * Tier 1 deterministic ABAP completion: patterns confident enough to answer
 * instantly, with no network call and nothing to sanitize or validate.
 * Currently one pattern -- closing the nearest still-open block (IF, LOOP,
 * CASE, TRY, DO, WHILE) when the cursor sits alone on a blank line -- but
 * that single pattern covers a large share of "just finished the block
 * body, need the closer" moments. Answering those for free means they never
 * compete with a real completion request for the debounce window, and they
 * can never be wrong the way an AI guess can.
 */
public final class AbapLocalCompleter {
    private static final Map<String, String> CLOSER_FOR_OPENER = Map.of(
        "IF", "ENDIF",
        "LOOP", "ENDLOOP",
        "CASE", "ENDCASE",
        "TRY", "ENDTRY",
        "DO", "ENDDO",
        "WHILE", "ENDWHILE"
    );
    private static final Pattern OPENER = Pattern.compile("(?i)^(IF|LOOP|CASE|TRY|DO|WHILE)\\b");
    private static final Pattern CLOSER = Pattern.compile("(?i)^(ENDIF|ENDLOOP|ENDCASE|ENDTRY|ENDDO|ENDWHILE)\\b");

    private AbapLocalCompleter() {}

    /** "" when no confident local suggestion applies -- the caller falls through to cache/AI. */
    public static String suggest(IDocument document, int offset) {
        if (document == null) return "";
        try {
            int line = document.getLineOfOffset(Math.max(0, Math.min(offset, document.getLength())));
            String currentLineText = lineText(document, line);
            if (!currentLineText.isBlank()) return "";

            Deque<String> alreadyClosed = new ArrayDeque<>();
            for (int scan = line - 1; scan >= 0; scan--) {
                String text = lineText(document, scan).trim();
                if (text.isEmpty()) continue;
                String upper = text.toUpperCase(Locale.ROOT);

                Matcher closer = CLOSER.matcher(upper);
                if (closer.find()) {
                    alreadyClosed.push(closer.group(1));
                    continue;
                }
                Matcher opener = OPENER.matcher(upper);
                if (opener.find()) {
                    String expectedCloser = CLOSER_FOR_OPENER.get(opener.group(1));
                    if (!alreadyClosed.isEmpty() && alreadyClosed.peek().equals(expectedCloser)) {
                        alreadyClosed.pop();
                        continue;
                    }
                    return expectedCloser + ".";
                }
                // A method/class boundary between the cursor and any opener
                // means there's nothing left open to close.
                if (upper.startsWith("ENDMETHOD") || upper.startsWith("ENDCLASS")
                    || upper.startsWith("METHOD ") || upper.startsWith("CLASS ")) {
                    return "";
                }
            }
            return "";
        } catch (BadLocationException error) {
            return "";
        }
    }

    private static String lineText(IDocument document, int line) throws BadLocationException {
        var info = document.getLineInformation(line);
        return document.get(info.getOffset(), info.getLength());
    }
}
