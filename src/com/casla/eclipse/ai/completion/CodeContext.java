package com.casla.eclipse.ai.completion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

public record CodeContext(
    String projectName,
    String filePath,
    String language,
    String packageName,
    String imports,
    /** Free-text structural signal (e.g. enclosing ABAP class/section/method); "" when unknown. */
    String structureHint,
    String beforeCursor,
    String afterCursor,
    int cursorOffset,
    long modificationStamp,
    String fingerprint
) {
    public CodeContext {
        structureHint = structureHint == null ? "" : structureHint;
    }

    public boolean isCurrent(IDocument document) {
        if (document == null || document.getLength() < cursorOffset) return false;
        if (document instanceof IDocumentExtension4 extension) {
            return extension.getModificationStamp() == modificationStamp;
        }
        return fingerprint.equals(fingerprint(document.get(), cursorOffset));
    }

    public static String fingerprint(String document, int cursorOffset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = cursorOffset + "\n" + document;
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString((cursorOffset + document).hashCode());
        }
    }
}
