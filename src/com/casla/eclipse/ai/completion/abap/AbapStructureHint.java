package com.casla.eclipse.ai.completion.abap;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

/**
 * Cheap backward scan for the ABAP structure enclosing the cursor: which
 * class the cursor is in, whether that is the DEFINITION or IMPLEMENTATION
 * block, and -- depending on which -- the current visibility SECTION or the
 * enclosing METHOD. Without this, the completion prompt has no way to tell a
 * class body from a chat transcript, which is how an undecorated prompt
 * produced a stray "METHOD get_min" pasted into a DEFINITION section: the
 * model had no structural signal to work from.
 *
 * Deliberately not a real ABAP parser: ABAP's CLASS...DEFINITION /
 * IMPLEMENTATION and METHOD...ENDMETHOD blocks do not nest, so a flat
 * backward line scan that stops at the first enclosing boundary is enough.
 */
public final class AbapStructureHint {
    private static final Pattern CLASS_HEADER =
        Pattern.compile("^CLASS\\s+(\\S+)\\s+(DEFINITION|IMPLEMENTATION)\\b");
    private static final Pattern METHOD_HEADER = Pattern.compile("^METHOD\\s+(\\S+)\\s*\\.(?:\\s*\".*)?$");
    private static final Pattern END_CLASS = Pattern.compile("^ENDCLASS\\s*\\.(?:\\s*\".*)?$");
    private static final Pattern END_METHOD = Pattern.compile("^ENDMETHOD\\s*\\.(?:\\s*\".*)?$");

    private record ScanResult(String className, String mode, String sectionName, String methodName, boolean methodClosed) {}

    private AbapStructureHint() {}

    public static String scan(IDocument document, int offset) {
        ScanResult result = scanInternal(document, offset);
        return result == null ? "" : describe(result);
    }

    /**
     * The name of the METHOD the cursor is textually inside, when scanning
     * back reaches an IMPLEMENTATION block without first crossing an
     * ENDMETHOD; "" for DEFINITION, between methods, or no enclosing class.
     * Used to look up the matching METHODS signature from the DEFINITION
     * section (see AbapMethodSignatureLookup) so completions inside a method
     * body know the real parameter names instead of guessing.
     */
    public static String enclosingMethodName(IDocument document, int offset) {
        ScanResult result = scanInternal(document, offset);
        if (result == null || !"IMPLEMENTATION".equals(result.mode()) || result.methodClosed()) return "";
        return result.methodName() == null ? "" : result.methodName();
    }

    private static ScanResult scanInternal(IDocument document, int offset) {
        if (document == null) return null;
        try {
            int startLine = document.getLineOfOffset(Math.max(0, Math.min(offset, document.getLength())));
            String methodName = null;
            boolean methodClosed = false;
            String sectionName = null;

            for (int line = startLine; line >= 0; line--) {
                String text = lineText(document, line).trim();
                if (text.isEmpty()) continue;
                String upper = text.toUpperCase(Locale.ROOT);

                Matcher classMatcher = CLASS_HEADER.matcher(upper);
                if (classMatcher.find()) {
                    return new ScanResult(classMatcher.group(1), classMatcher.group(2), sectionName, methodName, methodClosed);
                }
                if (END_CLASS.matcher(upper).matches()) {
                    return null;
                }

                if (methodName == null && !methodClosed) {
                    Matcher methodMatcher = METHOD_HEADER.matcher(upper);
                    if (methodMatcher.matches()) {
                        methodName = methodMatcher.group(1);
                    } else if (END_METHOD.matcher(upper).matches()) {
                        methodClosed = true;
                    }
                }

                if (sectionName == null) {
                    if (upper.startsWith("PUBLIC SECTION")) sectionName = "PUBLIC SECTION";
                    else if (upper.startsWith("PROTECTED SECTION")) sectionName = "PROTECTED SECTION";
                    else if (upper.startsWith("PRIVATE SECTION")) sectionName = "PRIVATE SECTION";
                }
            }
            return null;
        } catch (BadLocationException error) {
            return null;
        }
    }

    private static String describe(ScanResult r) {
        if ("DEFINITION".equals(r.mode())) {
            return r.sectionName() == null
                ? "Class " + r.className() + ", DEFINITION"
                : "Class " + r.className() + ", DEFINITION, " + r.sectionName();
        }
        return r.methodName() != null && !r.methodClosed()
            ? "Class " + r.className() + ", IMPLEMENTATION, inside METHOD " + r.methodName()
            : "Class " + r.className() + ", IMPLEMENTATION, between methods";
    }

    private static String lineText(IDocument document, int line) throws BadLocationException {
        var info = document.getLineInformation(line);
        return document.get(info.getOffset(), info.getLength());
    }
}
