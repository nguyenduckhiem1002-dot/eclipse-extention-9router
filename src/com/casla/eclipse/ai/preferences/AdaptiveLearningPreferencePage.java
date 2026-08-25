package com.casla.eclipse.ai.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.casla.eclipse.ai.learning.AdaptiveLearningStore;

/** Controls for local adaptive memory; no telemetry leaves the workstation. */
public final class AdaptiveLearningPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private Button enabled;
    private Spinner memoryLimit;
    private Label diagnostics;

    @Override public void init(IWorkbench workbench) {}

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group controls = new Group(root, SWT.NONE);
        controls.setText("Adaptive learning");
        controls.setLayout(new GridLayout(2, false));
        controls.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        enabled = new Button(controls, SWT.CHECK);
        enabled.setText("Enable local adaptive learning");
        enabled.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(controls, SWT.NONE).setText("Maximum local examples / objects");
        memoryLimit = new Spinner(controls, SWT.BORDER);
        memoryLimit.setMinimum(20);
        memoryLimit.setMaximum(1000);
        memoryLimit.setIncrement(20);
        memoryLimit.setSelection(300);
        memoryLimit.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label privacy = new Label(controls, SWT.WRAP);
        privacy.setText("Memory is stored only in Eclipse plugin state. Accepted examples are normalized and bounded; object memory stores skeletons rather than full documents. No learning telemetry is uploaded.");
        privacy.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        Group actions = new Group(root, SWT.NONE);
        actions.setText("Reset");
        actions.setLayout(new GridLayout(4, true));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        button(actions, "Reset all", () -> AdaptiveLearningStore.get().reset());
        button(actions, "Examples", () -> AdaptiveLearningStore.get().resetExamples());
        button(actions, "Objects", () -> AdaptiveLearningStore.get().resetObjects());
        button(actions, "Feedback", () -> AdaptiveLearningStore.get().resetFeedback());

        Group diag = new Group(root, SWT.NONE);
        diag.setText("Diagnostics");
        diag.setLayout(new GridLayout(1, false));
        diag.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        diagnostics = new Label(diag, SWT.WRAP);
        diagnostics.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Button refresh = new Button(diag, SWT.PUSH);
        refresh.setText("Refresh diagnostics");
        refresh.addListener(SWT.Selection, event -> refreshDiagnostics());

        load();
        return root;
    }

    private void button(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, event -> { action.run(); refreshDiagnostics(); });
    }

    private void load() {
        AdaptiveLearningStore store = AdaptiveLearningStore.get();
        enabled.setSelection(!store.isPaused());
        refreshDiagnostics();
    }

    private void refreshDiagnostics() {
        if (diagnostics != null && !diagnostics.isDisposed()) diagnostics.setText(AdaptiveLearningStore.get().diagnosticsSummary());
    }

    @Override
    public boolean performOk() {
        AdaptiveLearningStore store = AdaptiveLearningStore.get();
        store.setMemoryLimit(memoryLimit.getSelection());
        store.setPaused(!enabled.getSelection());
        return true;
    }

    @Override
    protected void performDefaults() {
        enabled.setSelection(true);
        memoryLimit.setSelection(300);
        super.performDefaults();
    }
}
