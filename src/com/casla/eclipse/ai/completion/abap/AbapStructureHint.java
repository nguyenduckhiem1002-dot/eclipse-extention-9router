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
    private static final Pattern METHOD_HEADER = Pattern.compile("^METHOD\\s+(\\S+)\\s*\\.$");
    private static final Pattern END_CLASS = Pattern.compile("^ENDCLASS\\s*\\.$");
    private static final Pattern END_METHOD = Pattern.compile("^ENDMETHOD\\s*\\.$");

    private AbapStructureHint() {}

    public static String scan(IDocument document, int offset) {
        if (document == null) return "";
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
                    return describe(classMatcher.group(1), classMatcher.group(2), sectionName, methodName, methodClosed);
                }
                if (END_CLASS.matcher(upper).matches()) {
                    return "";
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
            return "";
        } catch (BadLocationException error) {
            return "";
        }
    }

    private static String describe(
        String className, String mode, String sectionName, String methodName, boolean methodClosed
    ) {
        if ("DEFINITION".equals(mode)) {
            return sectionName == null
                ? "Class " + className + ", DEFINITION"
                : "Class " + className + ", DEFINITION, " + sectionName;
        }
        return methodName != null && !methodClosed
            ? "Class " + className + ", IMPLEMENTATION, inside METHOD " + methodName
            : "Class " + className + ", IMPLEMENTATION, between methods";
    }

    private static String lineText(IDocument document, int line) throws BadLocationException {
        var info = document.getLineInformation(line);
        return document.get(info.getOffset(), info.getLength());
    }
}
