/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import static android.app.ActivityThread.DEBUG_CONFIGURATION;
import static android.window.ConfigurationHelper.freeTextLayoutCachesIfNeeded;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.servertransaction.ClientTransactionListenerController;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.os.LocaleList;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Locale;

/**
 * A client side controller to handle process level configuration changes.
 * @hide
 */
class ConfigurationController {
    private static final String TAG = "ConfigurationController";

    private final ActivityThreadInternal mActivityThread;

    private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

    @GuardedBy("mResourcesManager")
    private @Nullable Configuration mPendingConfiguration;
    private @Nullable Configuration mCompatConfiguration;
    private @Nullable Configuration mConfiguration;

    ConfigurationController(@NonNull ActivityThreadInternal activityThread) {
        mActivityThread = activityThread;
    }

    /** Update the pending configuration. */
    Configuration updatePendingConfiguration(@NonNull Configuration config) {
        synchronized (mResourcesManager) {
            if (mPendingConfiguration == null || mPendingConfiguration.isOtherSeqNewer(config)) {
                mPendingConfiguration = config;
                return mPendingConfiguration;
            }
        }
        return null;
    }

    /** Get the pending configuration. */
    Configuration getPendingConfiguration(boolean clearPending) {
        Configuration outConfig = null;
        synchronized (mResourcesManager) {
            if (mPendingConfiguration != null) {
                outConfig = mPendingConfiguration;
                if (clearPending) {
                    mPendingConfiguration = null;
                }
            }
        }
        return outConfig;
    }

    /** Set the compatibility configuration. */
    void setCompatConfiguration(@NonNull Configuration config) {
        mCompatConfiguration = new Configuration(config);
    }

    /** Get the compatibility configuration. */
    Configuration getCompatConfiguration() {
        return mCompatConfiguration;
    }

    /** Apply the global compatibility configuration. */
    final Configuration applyCompatConfiguration() {
        Configuration config = mConfiguration;
        final int displayDensity = config.densityDpi;
        if (mCompatConfiguration == null) {
            mCompatConfiguration = new Configuration();
        }
        mCompatConfiguration.setTo(mConfiguration);
        if (mResourcesManager.applyCompatConfiguration(displayDensity, mCompatConfiguration)) {
            config = mCompatConfiguration;
        }
        return config;
    }

    /** Set the configuration. */
    void setConfiguration(@NonNull Configuration config) {
        mConfiguration = new Configuration(config);
    }

    /** Get current configuration. */
    Configuration getConfiguration() {
        return mConfiguration;
    }

    /**
     * Update the configuration to latest.
     * @param config The new configuration.
     */
    void handleConfigurationChanged(@NonNull Configuration config) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "configChanged");
        handleConfigurationChanged(config, null /* compat */);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Update the configuration to latest.
     * @param compat The new compatibility information.
     */
    void handleConfigurationChanged(@NonNull CompatibilityInfo compat) {
        handleConfigurationChanged(mConfiguration, compat);
        WindowManagerGlobal.getInstance().reportNewConfiguration(mConfiguration);
    }

    /**
     * Update the configuration to latest.
     * @param config The new configuration.
     * @param compat The new compatibility information.
     */
    void handleConfigurationChanged(@Nullable Configuration config,
            @Nullable CompatibilityInfo compat) {
        final ClientTransactionListenerController controller =
                ClientTransactionListenerController.getInstance();
        final Context contextToUpdate = ActivityThread.currentApplication();
        controller.onContextConfigurationPreChanged(contextToUpdate);
        try {
            handleConfigurationChangedInner(config, compat);
        } finally {
            controller.onContextConfigurationPostChanged(contextToUpdate);
        }
    }

    /**
     * Update the configuration to latest.
     * @param config The new configuration.
     * @param compat The new compatibility information.
     */
    private void handleConfigurationChangedInner(@Nullable Configuration config,
            @Nullable CompatibilityInfo compat) {
        int configDiff;
        boolean equivalent;

        // Get theme outside of synchronization to avoid nested lock.
        final Resources.Theme systemTheme = mActivityThread.getSystemContext().getTheme();
        final ContextImpl systemUiContext = mActivityThread.getSystemUiContextNoCreate();
        final Resources.Theme systemUiTheme =
                systemUiContext != null ? systemUiContext.getTheme() : null;
        synchronized (mResourcesManager) {
            if (mPendingConfiguration != null) {
                if (!mPendingConfiguration.isOtherSeqNewer(config)) {
                    config = mPendingConfiguration;
                    updateDefaultDensity(config.densityDpi);
                }
                mPendingConfiguration = null;
            }

            if (config == null) {
                return;
            }

            // This flag tracks whether the new configuration is fundamentally equivalent to the
            // existing configuration. This is necessary to determine whether non-activity callbacks
            // should receive notice when the only changes are related to non-public fields.
            // We do not gate calling {@link #performActivityConfigurationChanged} based on this
            // flag as that method uses the same check on the activity config override as well.
            equivalent = mConfiguration != null && (0 == mConfiguration.diffPublicOnly(config));

            if (DEBUG_CONFIGURATION) {
                Slog.v(TAG, "Handle configuration changed: " + config);
            }

            final Application app = mActivityThread.getApplication();
            final Resources appResources = app.getResources();
            mResourcesManager.applyConfigurationToResources(config, compat);
            updateLocaleListFromAppContext(app.getApplicationContext());

            if (mConfiguration == null) {
                mConfiguration = new Configuration();
            }
            if (!mConfiguration.isOtherSeqNewer(config) && compat == null) {
                return;
            }

            configDiff = mConfiguration.updateFrom(config);
            config = applyCompatConfiguration();
            HardwareRenderer.sendDeviceConfigurationForDebugging(config);

            if ((systemTheme.getChangingConfigurations() & configDiff) != 0) {
                systemTheme.rebase();
            }

            if (systemUiTheme != null
                    && (systemUiTheme.getChangingConfigurations() & configDiff) != 0) {
                systemUiTheme.rebase();
            }
        }

        final ArrayList<ComponentCallbacks2> callbacks =
                mActivityThread.collectComponentCallbacks(false /* includeUiContexts */);

        freeTextLayoutCachesIfNeeded(configDiff);

        if (callbacks != null) {
            final int size = callbacks.size();
            for (int i = 0; i < size; i++) {
                ComponentCallbacks2 cb = callbacks.get(i);
                if (!equivalent) {
                    performConfigurationChanged(cb, config);
                }
            }
        }
    }

    /**
     * Decides whether to update a component's configuration and whether to inform it.
     * @param cb The component callback to notify of configuration change.
     * @param newConfig The new configuration.
     */
    void performConfigurationChanged(@NonNull ComponentCallbacks2 cb,
            @NonNull Configuration newConfig) {
        // ContextThemeWrappers may override the configuration for that context. We must check and
        // apply any overrides defined.
        Configuration contextThemeWrapperOverrideConfig = null;
        if (cb instanceof ContextThemeWrapper) {
            final ContextThemeWrapper contextThemeWrapper = (ContextThemeWrapper) cb;
            contextThemeWrapperOverrideConfig = contextThemeWrapper.getOverrideConfiguration();
        }

        // Apply the ContextThemeWrapper override if necessary.
        // NOTE: Make sure the configurations are not modified, as they are treated as immutable
        // in many places.
        final Configuration configToReport = createNewConfigAndUpdateIfNotNull(
                newConfig, contextThemeWrapperOverrideConfig);
        cb.onConfigurationChanged(configToReport);
    }

    /** Update default density. */
    void updateDefaultDensity(int densityDpi) {
        if (!mActivityThread.isInDensityCompatMode()
                && densityDpi != Configuration.DENSITY_DPI_UNDEFINED
                && densityDpi != DisplayMetrics.DENSITY_DEVICE) {
            DisplayMetrics.DENSITY_DEVICE = densityDpi;
            Bitmap.setDefaultDensity(densityDpi);
        }
    }

    /** Get current default display dpi. This is only done to maintain @UnsupportedAppUsage. */
    int getCurDefaultDisplayDpi() {
        return mConfiguration.densityDpi;
    }

    /**
     * The LocaleList set for the app's resources may have been shuffled so that the preferred
     * Locale is at position 0. We must find the index of this preferred Locale in the
     * original LocaleList.
     */
    void updateLocaleListFromAppContext(@NonNull Context context) {
        final Locale bestLocale = context.getResources().getConfiguration().getLocales().get(0);
        final LocaleList newLocaleList = mResourcesManager.getConfiguration().getLocales();
        final int newLocaleListSize = newLocaleList.size();
        for (int i = 0; i < newLocaleListSize; i++) {
            if (bestLocale.equals(newLocaleList.get(i))) {
                LocaleList.setDefault(newLocaleList, i);
                return;
            }
        }

        // The app may have overridden the LocaleList with its own Locale
        // (not present in the available list). Push the chosen Locale
        // to the front of the list.
        LocaleList.setDefault(new LocaleList(bestLocale, newLocaleList));
    }

    /**
     * Creates a new Configuration only if override would modify base. Otherwise returns base.
     * @param base The base configuration.
     * @param override The update to apply to the base configuration. Can be null.
     * @return A Configuration representing base with override applied.
     */
    static Configuration createNewConfigAndUpdateIfNotNull(@NonNull Configuration base,
            @Nullable Configuration override) {
        if (override == null) {
            return base;
        }
        Configuration newConfig = new Configuration(base);
        newConfig.updateFrom(override);
        return newConfig;
    }

}
