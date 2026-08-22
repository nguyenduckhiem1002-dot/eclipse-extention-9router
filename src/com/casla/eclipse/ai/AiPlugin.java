package com.casla.eclipse.ai;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.casla.eclipse.ai.completion.AutoCompletionController;
import com.casla.eclipse.ai.runtime.AiRuntime;

public final class AiPlugin extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "com.casla.eclipse.ai";

    private static AiPlugin plugin;

    public static AiPlugin getDefault() {
        return plugin;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        AiRuntime.get().bootstrap();
        Display.getDefault().asyncExec(AutoCompletionController.get()::start);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        AutoCompletionController.get().stop();
        AiRuntime.get().shutdown();
        plugin = null;
        super.stop(context);
    }

    public static void logError(String message, Throwable error) {
        AiPlugin instance = plugin;
        if (instance != null) {
            instance.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
        }
    }
}
