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

        return new CodeContext(
            project,
            path,
            "Java",
            findPackage(fullDocument),
            findImports(fullDocument),
            document.get(beforeStart, safeOffset - beforeStart),
            document.get(safeOffset, afterEnd - safeOffset),
            safeOffset,
            modificationStamp(document),
            CodeContext.fingerprint(fullDocument, safeOffset)
        );
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
