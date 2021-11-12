/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.window;

import static android.window.ConfigurationHelper.diffPublicWithSizeBuckets;
import static android.window.ConfigurationHelper.freeTextLayoutCachesIfNeeded;
import static android.window.ConfigurationHelper.isDifferentDisplay;
import static android.window.ConfigurationHelper.shouldUpdateResources;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.IWindowToken;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.res.Configuration;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

/**
 * This class is used to receive {@link Configuration} changes from the associated window manager
 * node on the server side, and apply the change to the {@link Context#getResources() associated
 * Resources} of the attached {@link Context}. It is also used as
 * {@link Context#getWindowContextToken() the token of non-Activity UI Contexts}.
 *
 * @see WindowContext
 * @see android.view.IWindowManager#attachWindowContextToDisplayArea(IBinder, int, int, Bundle)
 *
 * @hide
 */
public class WindowTokenClient extends IWindowToken.Stub {
    private static final String TAG = WindowTokenClient.class.getSimpleName();

    /**
     * Attached {@link Context} for this window token to update configuration and resources.
     * Initialized by {@link #attachContext(Context)}.
     */
    private WeakReference<Context> mContextRef = null;

    private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

    private final Configuration mConfiguration = new Configuration();

    private boolean mShouldDumpConfigForIme;

    /**
     * Attaches {@code context} to this {@link WindowTokenClient}. Each {@link WindowTokenClient}
     * can only attach one {@link Context}.
     * <p>This method must be called before invoking
     * {@link android.view.IWindowManager#attachWindowContextToDisplayArea(IBinder, int, int,
     * Bundle)}.<p/>
     *
     * @param context context to be attached
     * @throws IllegalStateException if attached context has already existed.
     */
    public void attachContext(@NonNull Context context) {
        if (mContextRef != null) {
            throw new IllegalStateException("Context is already attached.");
        }
        mContextRef = new WeakReference<>(context);
        mConfiguration.setTo(context.getResources().getConfiguration());
        mShouldDumpConfigForIme = Build.IS_DEBUGGABLE
                && context instanceof AbstractInputMethodService;
    }

    /**
     * Called when {@link Configuration} updates from the server side receive.
     *
     * @param newConfig the updated {@link Configuration}
     * @param newDisplayId the updated {@link android.view.Display} ID
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId) {
        onConfigurationChanged(newConfig, newDisplayId, true /* shouldReportConfigChange */);
    }

    // TODO(b/192048581): rewrite this method based on WindowContext and WindowProviderService
    //  are inherited from WindowProvider.
    /**
     * Called when {@link Configuration} updates from the server side receive.
     *
     * Similar to {@link #onConfigurationChanged(Configuration, int)}, but adds a flag to control
     * whether to dispatch configuration update or not.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId,
            boolean shouldReportConfigChange) {
        final Context context = mContextRef.get();
        if (context == null) {
            return;
        }
        final boolean displayChanged = isDifferentDisplay(context.getDisplayId(), newDisplayId);
        final boolean shouldUpdateResources = shouldUpdateResources(this, mConfiguration,
                newConfig, newConfig /* overrideConfig */, displayChanged,
                null /* configChanged */);

        if (!shouldUpdateResources && mShouldDumpConfigForIme) {
            Log.d(TAG, "Configuration not dispatch to IME because configuration is up"
                    + " to date. Current config=" + context.getResources().getConfiguration()
                    + ", reported config=" + mConfiguration
                    + ", updated config=" + newConfig);
        }

        if (shouldUpdateResources) {
            // TODO(ag/9789103): update resource manager logic to track non-activity tokens
            mResourcesManager.updateResourcesForActivity(this, newConfig, newDisplayId);

            if (shouldReportConfigChange && context instanceof WindowContext) {
                final WindowContext windowContext = (WindowContext) context;
                ActivityThread.currentActivityThread().getHandler().post(
                        () -> windowContext.dispatchConfigurationChanged(newConfig));
            }

            // Dispatch onConfigurationChanged only if there's a significant public change to
            // make it compatible with the original behavior.
            final Configuration[] sizeConfigurations = context.getResources()
                    .getSizeConfigurations();
            final SizeConfigurationBuckets buckets = sizeConfigurations != null
                    ? new SizeConfigurationBuckets(sizeConfigurations) : null;
            final int diff = diffPublicWithSizeBuckets(mConfiguration, newConfig, buckets);

            if (shouldReportConfigChange && diff != 0
                    && context instanceof WindowProviderService) {
                final WindowProviderService windowProviderService = (WindowProviderService) context;
                ActivityThread.currentActivityThread().getHandler().post(
                        () -> windowProviderService.onConfigurationChanged(newConfig));
            }
            freeTextLayoutCachesIfNeeded(diff);
            if (mShouldDumpConfigForIme) {
                if (!shouldReportConfigChange) {
                    Log.d(TAG, "Only apply configuration update to Resources because "
                            + "shouldReportConfigChange is false.\n" + Debug.getCallers(5));
                } else if (diff == 0) {
                    Log.d(TAG, "Configuration not dispatch to IME because configuration has no "
                            + " public difference with updated config. "
                            + " Current config=" + context.getResources().getConfiguration()
                            + ", reported config=" + mConfiguration
                            + ", updated config=" + newConfig);
                }
            }
            mConfiguration.setTo(newConfig);
        }
        if (displayChanged) {
            context.updateDisplay(newDisplayId);
        }
    }

    @Override
    public void onWindowTokenRemoved() {
        final Context context = mContextRef.get();
        if (context != null) {
            context.destroy();
            mContextRef.clear();
        }
    }
}
