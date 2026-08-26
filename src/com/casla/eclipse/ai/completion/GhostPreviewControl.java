package com.casla.eclipse.ai.completion;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

/**
 * Floating, non-document preview for multi-line or mid-line ghost text. It
 * never changes StyledText layout, so existing source lines are not pushed,
 * indented or painted over. The editor keeps keyboard focus; Tab/word/line
 * acceptance is still owned by GhostTextController.
 */
final class GhostPreviewControl {
    private static final int MAX_VISIBLE_LINES = 14;
    private static final int MIN_WIDTH = 360;
    private static final int MAX_WIDTH = 980;
    private Shell shell;
    private StyledText preview;

    void show(StyledText editor, int widgetOffset, String suggestion) {
        if (editor == null || editor.isDisposed() || suggestion == null || suggestion.isBlank()) {
            hide();
            return;
        }
        ensureCreated(editor);
        if (shell == null || shell.isDisposed()) return;

        preview.setFont(editor.getFont());
        preview.setForeground(editor.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        preview.setBackground(editor.getBackground());
        preview.setText(suggestion);

        String[] lines = suggestion.split("\r?\n", -1);
        int lineHeight = Math.max(16, editor.getLineHeight(widgetOffset));
        int longest = MIN_WIDTH;
        GC gc = new GC(editor);
        try {
            gc.setFont(editor.getFont());
            for (String line : lines) longest = Math.max(longest, gc.textExtent(line).x + 30);
        } finally {
            gc.dispose();
        }
        int editorWidth = Math.max(MIN_WIDTH, editor.getClientArea().width);
        int width = Math.min(MAX_WIDTH, Math.min(longest, Math.max(MIN_WIDTH, (int) (editorWidth * 0.82))));
        int visibleLines = Math.max(1, Math.min(MAX_VISIBLE_LINES, lines.length));
        int height = visibleLines * lineHeight + 14;

        Point local = editor.getLocationAtOffset(Math.max(0, Math.min(widgetOffset, editor.getCharCount())));
        Point display = editor.toDisplay(local.x, local.y + lineHeight + 3);
        Monitor monitor = editor.getMonitor();
        Rectangle area = monitor == null ? editor.getDisplay().getPrimaryMonitor().getClientArea() : monitor.getClientArea();
        int x = Math.max(area.x + 4, Math.min(display.x, area.x + area.width - width - 4));
        int y = display.y;
        if (y + height > area.y + area.height - 4) {
            y = Math.max(area.y + 4, editor.toDisplay(local.x, local.y).y - height - 3);
        }

        shell.setBounds(x, y, width, height);
        preview.setBounds(6, 4, width - 12, height - 8);
        shell.setVisible(true);
        shell.moveAbove(null);
    }

    void hide() {
        if (shell != null && !shell.isDisposed()) shell.setVisible(false);
    }

    void dispose() {
        if (shell != null && !shell.isDisposed()) shell.dispose();
        shell = null;
        preview = null;
    }

    private void ensureCreated(StyledText editor) {
        if (shell != null && !shell.isDisposed()) return;
        shell = new Shell(editor.getShell(), SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL);
        shell.setBackground(editor.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BORDER));
        preview = new StyledText(shell, SWT.READ_ONLY | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        preview.setEditable(false);
    }
}
