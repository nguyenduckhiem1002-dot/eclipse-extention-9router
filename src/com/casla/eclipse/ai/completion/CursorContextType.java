package com.casla.eclipse.ai.completion;

public enum CursorContextType {
    CODE,
    JAVADOC,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING_LITERAL;

    public boolean isComment() {
        return this == JAVADOC || this == LINE_COMMENT || this == BLOCK_COMMENT;
    }
}
