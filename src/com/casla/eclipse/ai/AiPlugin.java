package com.casla.eclipse.ai;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.casla.eclipse.ai.completion.GhostTextController;
import com.casla.eclipse.ai.learning.AdaptiveLearningController;
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
        // Ghost text replaces the old automatic Content Assist popup: the
        // popup stole focus and blocked typing while suggestions loaded,
        // which is exactly the jank inline suggestions are meant to avoid.
        Display.getDefault().asyncExec(() -> {
            GhostTextController.get().start();
            AdaptiveLearningController.get().start();
        });
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        AdaptiveLearningController.get().stop();
        GhostTextController.get().stop();
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

    public static void logInfo(String message) {
        AiPlugin instance = plugin;
        if (instance != null) {
            instance.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }
}
