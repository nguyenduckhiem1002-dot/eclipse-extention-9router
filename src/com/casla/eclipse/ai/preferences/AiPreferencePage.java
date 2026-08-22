package com.casla.eclipse.ai.preferences;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.api.ModelPreference;
import com.casla.eclipse.ai.api.ModelSelectionMode;
import com.casla.eclipse.ai.runtime.AiRuntime;
import com.casla.eclipse.ai.runtime.ConnectionTestReport;

public final class AiPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    /** Ordered for display; must stay in sync with CompletionSettings.REASONING_EFFORT_LEVELS. */
    private static final String[] REASONING_EFFORT_ITEMS =
        {"default", "none", "minimal", "low", "medium", "high"};

    private final AiPreferences preferences = new AiPreferences();

    private Text baseUrlText;
    private Text apiKeyText;
    private Button testButton;
    private Label connectionStatus;
    private Label connectionDetails;
    private Button autoModeButton;
    private Button manualModeButton;
    private Label autoModelLabel;
    private Text modelFilterText;
    private Combo manualModelCombo;
    private Button refreshButton;
    private Label catalogHint;
    private String[] allModelIds = new String[0];
    private String lastModelDraft = "";
    private Spinner maxTokensSpinner;
    private Spinner temperatureSpinner;
    private Spinner timeoutSpinner;
    private Spinner contextBeforeSpinner;
    private Spinner contextAfterSpinner;
    private Button automaticSuggestionButton;
    private Spinner debounceSpinner;
    private Combo reasoningEffortCombo;
    private boolean initializing;
    private boolean testing;

    public AiPreferencePage() {
        setPreferenceStore(AiPlugin.getDefault().getPreferenceStore());
        setDescription("Configure an OpenAI-compatible endpoint for Java code completion.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // No workbench-specific initialization.
    }

    @Override
    protected Control createContents(Composite parent) {
        initializing = true;
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createConnectionGroup(root);
        createModelGroup(root);
        createCompletionGroup(root);
        loadPersistedValues(false);
        // The catalog is already loaded once bootstrap verified the endpoint.
        // Without this the dropdown stays empty until Test/Refresh is pressed.
        populateModels(AiRuntime.get().catalog());
        updateModeControls();
        updateRuntimeStatus();
        initializing = false;
        return root;
    }

    private void createConnectionGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Connection");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(group, SWT.NONE).setText("Base URL");
        baseUrlText = new Text(group, SWT.BORDER);
        baseUrlText.setLayoutData(span(3));
        baseUrlText.setMessage("http://localhost:20128/v1");

        new Label(group, SWT.NONE).setText("API key");
        apiKeyText = new Text(group, SWT.BORDER | SWT.PASSWORD);
        apiKeyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        testButton = new Button(group, SWT.PUSH);
        testButton.setText("Test connection");
        testButton.addListener(SWT.Selection, event -> runConnectionTest());

        new Label(group, SWT.NONE).setText("Status");
        connectionStatus = new Label(group, SWT.NONE);
        connectionStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        connectionDetails = new Label(group, SWT.WRAP);
        GridData detailsData = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
        // Reserve the room the report needs up front. Without a fixed height the
        // group keeps its old bounds when the report grows, and the rows below
        // are clipped instead of being pushed down.
        detailsData.heightHint = convertHeightInCharsToPixels(4);
        connectionDetails.setLayoutData(detailsData);

        baseUrlText.addModifyListener(event -> onConnectionDraftChanged());
        apiKeyText.addModifyListener(event -> onConnectionDraftChanged());
    }

    private void createModelGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Model");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(group, SWT.NONE).setText("Selection");
        Composite modes = new Composite(group, SWT.NONE);
        GridLayout modesLayout = new GridLayout(2, false);
        modesLayout.marginWidth = 0;
        modesLayout.marginHeight = 0;
        modes.setLayout(modesLayout);
        modes.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        autoModeButton = new Button(modes, SWT.RADIO);
        autoModeButton.setText("Auto");
        manualModeButton = new Button(modes, SWT.RADIO);
        manualModeButton.setText("Manual");
        autoModeButton.addListener(SWT.Selection, event -> onModelDraftChanged());
        manualModeButton.addListener(SWT.Selection, event -> onModelDraftChanged());

        new Label(group, SWT.NONE).setText("Auto model");
        autoModelLabel = new Label(group, SWT.NONE);
        autoModelLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        new Label(group, SWT.NONE).setText("Filter");
        modelFilterText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        modelFilterText.setMessage("Type to narrow the model list");
        modelFilterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        refreshButton = new Button(group, SWT.PUSH);
        refreshButton.setText("Refresh models");
        refreshButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        refreshButton.addListener(SWT.Selection, event -> runConnectionTest());
        modelFilterText.addModifyListener(event -> applyModelFilter());

        new Label(group, SWT.NONE).setText("Manual model");
        manualModelCombo = new Combo(group, SWT.DROP_DOWN | SWT.BORDER);
        manualModelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        manualModelCombo.setVisibleItemCount(15);
        manualModelCombo.addModifyListener(event -> onModelDraftChanged());

        catalogHint = new Label(group, SWT.WRAP);
        GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
        hintData.heightHint = convertHeightInCharsToPixels(2);
        catalogHint.setLayoutData(hintData);
    }

    private void createCompletionGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Completion");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(group, SWT.NONE).setText("Max tokens");
        maxTokensSpinner = spinner(group, 16, 4096, 128, 32);
        new Label(group, SWT.NONE).setText("Temperature");
        temperatureSpinner = spinner(group, 0, 20, 1, 1);
        temperatureSpinner.setDigits(1);

        new Label(group, SWT.NONE).setText("Timeout (seconds)");
        timeoutSpinner = spinner(group, 3, 180, 30, 5);
        new Label(group, SWT.NONE).setText("Debounce (ms)");
        debounceSpinner = spinner(group, 200, 5000, 500, 100);

        new Label(group, SWT.NONE).setText("Context before");
        contextBeforeSpinner = spinner(group, 500, 100_000, 6000, 500);
        new Label(group, SWT.NONE).setText("Context after");
        contextAfterSpinner = spinner(group, 0, 50_000, 2000, 500);

        new Label(group, SWT.NONE).setText("Reasoning effort");
        reasoningEffortCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        reasoningEffortCombo.setItems(REASONING_EFFORT_ITEMS);
        reasoningEffortCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        Label reasoningNote = new Label(group, SWT.WRAP);
        reasoningNote.setText("\"default\" lets the endpoint choose. Lower levels cut latency on models with reasoning that can be turned down.");
        reasoningNote.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1));

        automaticSuggestionButton = new Button(group, SWT.CHECK);
        automaticSuggestionButton.setText("Enable automatic ghost text (Copilot style)");
        automaticSuggestionButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));

        Label note = new Label(group, SWT.WRAP);
        note.setText("Inline ghost text appears as you type. Tab: accept all, Ctrl+Right / Alt+]: accept word, Ctrl+Down: accept line, Esc: dismiss.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1));
    }

    private void onConnectionDraftChanged() {
        if (initializing) return;
        AiRuntime.get().invalidateDraftConfiguration();
        autoModelLabel.setText("Not resolved");
        showStatus("● Not verified", "Configuration changed — test the connection again.", SWT.COLOR_DARK_YELLOW);
        validatePage();
    }

    private void onModelDraftChanged() {
        if (initializing) return;
        // Picking or typing in the combo must not reset a healthy runtime state
        // unless the model identifier really changed.
        String draft = manualModeButton.getSelection() ? manualModelCombo.getText().trim() : "";
        if (manualModeButton.getSelection() && draft.equals(lastModelDraft)) return;
        lastModelDraft = draft;
        if (autoModeButton.getSelection()) {
            AiRuntime.get().resolveAutoFromCatalog();
            var resolved = AiRuntime.get().snapshot().resolvedModelId();
            autoModelLabel.setText(resolved.isBlank() ? "Not resolved" : resolved + " (auto)");
        } else {
            AiRuntime.get().invalidateDraftModel();
            autoModelLabel.setText("Not resolved");
        }
        updateModeControls();
        if (autoModeButton.getSelection() && AiRuntime.get().snapshot().canComplete()) {
            showStatus("● Connected", "Auto model is ready.", SWT.COLOR_DARK_GREEN);
        } else {
            showStatus("● Model not resolved", "Model selection changed — test or refresh the connection.", SWT.COLOR_DARK_YELLOW);
        }
    }

    private void runConnectionTest() {
        String validation = ConnectionConfig.validateBaseUrl(baseUrlText.getText());
        if (validation != null) {
            setErrorMessage(validation);
            setValid(false);
            return;
        }
        if (apiKeyText.getText().isBlank()) {
            setErrorMessage("API key is required.");
            setValid(false);
            return;
        }

        setErrorMessage(null);
        setValid(true);
        testing = true;
        setButtonsEnabled(false);
        showStatus("● Checking", "Connecting to endpoint…", SWT.COLOR_DARK_YELLOW);

        ConnectionConfig connection = draftConnection();
        ModelPreference modelPreference = draftModelPreference();
        var display = testButton.getDisplay();
        Job job = new Job("Test AI Code Assistant connection") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                ConnectionTestReport report = AiRuntime.get().testConnection(connection, modelPreference, monitor);
                display.asyncExec(() -> {
                    if (getControl() == null || getControl().isDisposed()) return;
                    testing = false;
                    setButtonsEnabled(true);
                    applyTestReport(report);
                    populateModels(AiRuntime.get().catalog());
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void applyTestReport(ConnectionTestReport report) {
        StringBuilder details = new StringBuilder();
        if (report.endpointReachable()) {
            details.append("✓ Endpoint responded — ").append(report.latencyMillis()).append(" ms\n");
        } else {
            details.append("✗ Endpoint is unreachable\n");
        }
        if (Boolean.TRUE.equals(report.authenticationValid())) {
            details.append("✓ API key accepted\n");
        } else if (Boolean.FALSE.equals(report.authenticationValid())) {
            details.append("✗ API key is invalid or unauthorized\n");
        } else {
            details.append("? Authentication could not be verified separately\n");
        }
        if (report.catalogSupported()) {
            details.append("✓ Found ").append(report.modelCount()).append(" models");
        } else {
            details.append("ⓘ Endpoint does not support /models; enter an ID manually");
        }
        if (!report.resolvedModelId().isBlank()) {
            details.append("\n✓ Selected ").append(report.resolvedModelId());
            autoModelLabel.setText(report.resolvedModelId() + (autoModeButton.getSelection() ? " (auto)" : ""));
        }

        if (report.success()) {
            showStatus("● Connected", details.toString(), SWT.COLOR_DARK_GREEN);
        } else if (report.endpointReachable()) {
            showStatus("● Connected with limitations", details + "\n" + report.message(), SWT.COLOR_DARK_YELLOW);
        } else {
            showStatus("● Connection error", details + "\n" + report.message(), SWT.COLOR_RED);
        }
        catalogHint.setText(report.catalogSupported()
            ? "Model list was loaded from the endpoint."
            : "The model field is editable because this endpoint cannot list models.");
        reflow();
    }

    /**
     * Re-lays out the whole page and, when the preference dialog hosts it inside
     * a scrolled area, updates the scroll extent. Laying out only the group that
     * changed leaves its parent at the old size and clips the rows below it.
     */
    private void reflow() {
        Control control = getControl();
        if (control == null || control.isDisposed()) return;
        for (Composite composite = (Composite) control; composite != null; composite = composite.getParent()) {
            composite.layout(true, true);
            if (composite instanceof ScrolledComposite scrolled && scrolled.getContent() != null) {
                scrolled.setMinSize(scrolled.getContent().computeSize(SWT.DEFAULT, SWT.DEFAULT));
                return;
            }
            if (composite == getShell()) return;
        }
    }

    private void applyModelFilter() {
        if (manualModelCombo == null || manualModelCombo.isDisposed()) return;
        String filter = modelFilterText.getText().trim().toLowerCase(Locale.ROOT);
        String selected = manualModelCombo.getText();
        String[] items = filter.isEmpty()
            ? allModelIds.clone()
            : Arrays.stream(allModelIds)
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(filter))
                .toArray(String[]::new);
        boolean wasInitializing = initializing;
        initializing = true;
        try {
            manualModelCombo.setItems(items);
            manualModelCombo.setText(selected);
        } finally {
            initializing = wasInitializing;
        }
    }

    private void populateModels(List<ModelInfo> models) {
        allModelIds = models.stream().map(ModelInfo::id).sorted().toArray(String[]::new);
        applyModelFilter();
    }

    private void updateModeControls() {
        if (manualModelCombo == null) return;
        boolean manual = manualModeButton.getSelection();
        manualModelCombo.setEnabled(manual && !testing);
        modelFilterText.setEnabled(manual && !testing);
        autoModelLabel.setEnabled(!manual);
    }

    private void updateRuntimeStatus() {
        var snapshot = AiRuntime.get().snapshot();
        if (snapshot.canComplete()) {
            autoModelLabel.setText(snapshot.resolvedModelId());
            showStatus("● Connected", "AI completion is ready.", SWT.COLOR_DARK_GREEN);
        } else {
            autoModelLabel.setText(snapshot.resolvedModelId().isBlank() ? "Not resolved" : snapshot.resolvedModelId());
            showStatus("● Not verified", snapshot.message(), SWT.COLOR_DARK_YELLOW);
        }
    }

    private void showStatus(String title, String details, int colorId) {
        if (connectionStatus == null || connectionStatus.isDisposed()) return;
        Color color = connectionStatus.getDisplay().getSystemColor(colorId);
        connectionStatus.setForeground(color);
        connectionStatus.setText(title);
        connectionDetails.setText(details == null ? "" : details);
    }

    private void setButtonsEnabled(boolean enabled) {
        testButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        updateModeControls();
    }

    private void validatePage() {
        String validation = ConnectionConfig.validateBaseUrl(baseUrlText.getText());
        if (validation == null && apiKeyText.getText().isBlank()) validation = "API key is required.";
        setErrorMessage(validation);
        setValid(validation == null);
    }

    @Override
    public boolean performOk() {
        validatePage();
        if (!isValid()) return false;
        ConnectionConfig oldConnection = preferences.connection();
        ConnectionConfig connection = draftConnection();
        ModelPreference modelPreference = draftModelPreference();
        if (!oldConnection.equals(connection)) modelPreference = modelPreference.clearAutoResolution();

        try {
            preferences.saveConnection(connection);
            preferences.saveModelPreference(modelPreference);
            preferences.saveCompletionSettings(draftCompletionSettings());
            AiRuntime.get().commitConfiguration(connection, modelPreference);
            return true;
        } catch (Exception error) {
            AiPlugin.logError("Could not save AI Code Assistant preferences.", error);
            setErrorMessage("Could not save the API key to Eclipse Secure Storage.");
            return false;
        }
    }

    @Override
    public boolean performCancel() {
        AiRuntime.get().reloadPersistedAsync();
        return super.performCancel();
    }

    @Override
    protected void performDefaults() {
        initializing = true;
        loadPersistedValues(true);
        apiKeyText.setText("");
        initializing = false;
        AiRuntime.get().invalidateDraftConfiguration();
        updateModeControls();
        showStatus("● Not verified", "Defaults loaded — test the connection before applying.", SWT.COLOR_DARK_YELLOW);
        super.performDefaults();
    }

    private void loadPersistedValues(boolean defaults) {
        var store = getPreferenceStore();
        String baseUrl = defaults
            ? store.getDefaultString(PreferenceConstants.BASE_URL)
            : preferences.connection().baseUrl();
        ModelPreference model = defaults
            ? new ModelPreference(ModelSelectionMode.AUTO, store.getDefaultString(PreferenceConstants.MANUAL_MODEL_ID), "", "")
            : preferences.modelPreference();
        CompletionSettings completion = defaults
            ? new CompletionSettings(
                store.getDefaultInt(PreferenceConstants.MAX_TOKENS),
                Double.parseDouble(store.getDefaultString(PreferenceConstants.TEMPERATURE)),
                store.getDefaultInt(PreferenceConstants.TIMEOUT_SECONDS),
                store.getDefaultInt(PreferenceConstants.CONTEXT_BEFORE),
                store.getDefaultInt(PreferenceConstants.CONTEXT_AFTER),
                store.getDefaultBoolean(PreferenceConstants.AUTOMATIC_SUGGESTION),
                store.getDefaultInt(PreferenceConstants.DEBOUNCE_MILLIS),
                store.getDefaultString(PreferenceConstants.REASONING_EFFORT)
            )
            : preferences.completionSettings();

        baseUrlText.setText(baseUrl);
        if (!defaults) apiKeyText.setText(preferences.connection().apiKey());
        autoModeButton.setSelection(model.mode() == ModelSelectionMode.AUTO);
        manualModeButton.setSelection(model.mode() == ModelSelectionMode.MANUAL);
        manualModelCombo.setText(model.manualModelId());
        lastModelDraft = model.mode() == ModelSelectionMode.MANUAL ? model.manualModelId().trim() : "";
        autoModelLabel.setText(model.lastResolvedAutoId().isBlank() ? "Not resolved" : model.lastResolvedAutoId());
        maxTokensSpinner.setSelection(completion.maxTokens());
        temperatureSpinner.setSelection((int) Math.round(completion.temperature() * 10));
        timeoutSpinner.setSelection(completion.timeoutSeconds());
        contextBeforeSpinner.setSelection(completion.contextBefore());
        contextAfterSpinner.setSelection(completion.contextAfter());
        automaticSuggestionButton.setSelection(completion.automaticSuggestion());
        debounceSpinner.setSelection(completion.debounceMillis());
        reasoningEffortCombo.setText(completion.reasoningEffort());
    }

    private ConnectionConfig draftConnection() {
        return new ConnectionConfig(baseUrlText.getText(), apiKeyText.getText());
    }

    private ModelPreference draftModelPreference() {
        ModelPreference persisted = preferences.modelPreference();
        var runtime = AiRuntime.get().snapshot();
        String resolvedAuto = autoModeButton.getSelection() && runtime.canComplete()
            ? runtime.resolvedModelId()
            : persisted.lastResolvedAutoId();
        return new ModelPreference(
            autoModeButton.getSelection() ? ModelSelectionMode.AUTO : ModelSelectionMode.MANUAL,
            manualModelCombo.getText(),
            resolvedAuto,
            persisted.lastKnownGoodModel()
        );
    }

    private CompletionSettings draftCompletionSettings() {
        return new CompletionSettings(
            maxTokensSpinner.getSelection(),
            temperatureSpinner.getSelection() / 10.0,
            timeoutSpinner.getSelection(),
            contextBeforeSpinner.getSelection(),
            contextAfterSpinner.getSelection(),
            automaticSuggestionButton.getSelection(),
            debounceSpinner.getSelection(),
            reasoningEffortCombo.getText()
        );
    }

    private static Spinner spinner(
        Composite parent,
        int minimum,
        int maximum,
        int selection,
        int increment
    ) {
        Spinner spinner = new Spinner(parent, SWT.BORDER);
        spinner.setMinimum(minimum);
        spinner.setMaximum(maximum);
        spinner.setSelection(selection);
        spinner.setIncrement(increment);
        spinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return spinner;
    }

    private static GridData span(int columns) {
        return new GridData(SWT.FILL, SWT.CENTER, true, false, columns, 1);
    }
}
