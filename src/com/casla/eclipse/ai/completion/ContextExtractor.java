package com.casla.eclipse.ai.completion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

import com.casla.eclipse.ai.api.CompletionSettings;

public final class ContextExtractor {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[\\w.*]+\\s*;");

    public CodeContext extract(
        ICompilationUnit compilationUnit,
        IDocument document,
        int offset,
        CompletionSettings settings
    ) throws BadLocationException {
        int safeOffset = Math.max(0, Math.min(offset, document.getLength()));
        String fullDocument = document.get();
        int beforeStart = Math.max(0, safeOffset - settings.contextBefore());
        int afterEnd = Math.min(document.getLength(), safeOffset + settings.contextAfter());

        String project = compilationUnit != null && compilationUnit.getJavaProject() != null
            ? compilationUnit.getJavaProject().getElementName()
            : "";
        String path = compilationUnit != null && compilationUnit.getPath() != null
            ? compilationUnit.getPath().toString()
            : "";

        CursorContextType contextType = detectCursorContext(fullDocument, safeOffset);
        var relatedFiles = new RelatedFileCollector().collect(compilationUnit, path);

        return new CodeContext(
            project,
            path,
            "Java",
            findPackage(fullDocument),
            findImports(fullDocument),
            "",
            document.get(beforeStart, safeOffset - beforeStart),
            document.get(safeOffset, afterEnd - safeOffset),
            safeOffset,
            modificationStamp(document),
            CodeContext.fingerprint(fullDocument, safeOffset),
            contextType,
            relatedFiles
        );
    }

    public static CursorContextType detectCursorContext(String doc, int offset) {
        return detectCursorContext(doc, offset, "Java");
    }

    public static CursorContextType detectCursorContext(String doc, int offset, String language) {
        if (doc == null || doc.isEmpty() || offset <= 0) return CursorContextType.CODE;
        int safeOffset = Math.min(offset, doc.length());

        int lineStart = doc.lastIndexOf('\n', safeOffset - 1);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        String linePrefix = doc.substring(lineStart, safeOffset);

        if ("ABAP".equalsIgnoreCase(language)) {
            return detectAbapCursorContext(linePrefix);
        }

        int slashIdx = linePrefix.indexOf("//");
        if (slashIdx >= 0) {
            boolean inQuote = false;
            for (int i = 0; i < slashIdx; i++) {
                if (linePrefix.charAt(i) == '"' && (i == 0 || linePrefix.charAt(i - 1) != '\\')) {
                    inQuote = !inQuote;
                }
            }
            if (!inQuote) {
                return CursorContextType.LINE_COMMENT;
            }
        }

        int quoteCount = 0;
        for (int i = 0; i < linePrefix.length(); i++) {
            if (linePrefix.charAt(i) == '"' && (i == 0 || linePrefix.charAt(i - 1) != '\\')) {
                quoteCount++;
            }
        }
        if (quoteCount % 2 != 0) {
            return CursorContextType.STRING_LITERAL;
        }

        String prefix = doc.substring(0, safeOffset);
        int lastBlockStart = prefix.lastIndexOf("/*");
        int lastBlockEnd = prefix.lastIndexOf("*/");
        if (lastBlockStart >= 0 && lastBlockStart > lastBlockEnd) {
            if (prefix.startsWith("/**", lastBlockStart)) {
                return CursorContextType.JAVADOC;
            }
            return CursorContextType.BLOCK_COMMENT;
        }

        return CursorContextType.CODE;
    }

    private static CursorContextType detectAbapCursorContext(String linePrefix) {
        String trimmed = linePrefix.trim();
        // 1. ABAP full-line comment (* at column 1 or first non-whitespace)
        if (linePrefix.startsWith("*") || trimmed.startsWith("*")) {
            return CursorContextType.LINE_COMMENT;
        }

        // 2. Scan linePrefix from left to right tracking string / comment state
        boolean inSingleQuote = false;
        boolean inTemplate = false;

        for (int i = 0; i < linePrefix.length(); i++) {
            char c = linePrefix.charAt(i);
            if (c == '\'' && !inTemplate) {
                // ABAP escapes single quote with double single-quote: ''
                if (inSingleQuote && i + 1 < linePrefix.length() && linePrefix.charAt(i + 1) == '\'') {
                    i++; // skip escaped quote
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (c == '|' && !inSingleQuote) {
                // ABAP string template |...|
                if (inTemplate && i > 0 && linePrefix.charAt(i - 1) == '\\') {
                    // escaped
                } else {
                    inTemplate = !inTemplate;
                }
            } else if (c == '"' && !inSingleQuote && !inTemplate) {
                // ABAP inline comment begins at unquoted "
                return CursorContextType.LINE_COMMENT;
            }
        }

        if (inSingleQuote || inTemplate) {
            return CursorContextType.STRING_LITERAL;
        }

        return CursorContextType.CODE;
    }

    private static String findPackage(String source) {
        Matcher matcher = PACKAGE.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String findImports(String source) {
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder imports = new StringBuilder();
        while (matcher.find() && imports.length() < 2000) {
            imports.append(matcher.group().trim()).append('\n');
        }
        return imports.toString().stripTrailing();
    }

    private static long modificationStamp(IDocument document) {
        return document instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }
}
