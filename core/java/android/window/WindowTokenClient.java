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
import android.os.Bundle;
import android.os.IBinder;

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
    /**
     * Attached {@link Context} for this window token to update configuration and resources.
     * Initialized by {@link #attachContext(Context)}.
     */
    private WeakReference<Context> mContextRef = null;

    private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

    private final Configuration mConfiguration = new Configuration();

    /**
     * Attaches {@code context} to this {@link WindowTokenClient}. Each {@link WindowTokenClient}
     * can only attach one {@link Context}.
     * <p>This method must be called before invoking
     * {@link android.view.IWindowManager#attachWindowContextToDisplayArea(IBinder, int, int,
     * Bundle, boolean)}.<p/>
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
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId) {
        final Context context = mContextRef.get();
        if (context == null) {
            return;
        }
        final boolean displayChanged = isDifferentDisplay(context.getDisplayId(), newDisplayId);
        final boolean shouldUpdateResources = shouldUpdateResources(this, mConfiguration,
                newConfig, newConfig /* overrideConfig */, displayChanged,
                null /* configChanged */);

        if (shouldUpdateResources) {
            // TODO(ag/9789103): update resource manager logic to track non-activity tokens
            mResourcesManager.updateResourcesForActivity(this, newConfig, newDisplayId);

            if (context instanceof WindowContext) {
                ActivityThread.currentActivityThread().getHandler().post(
                        () -> ((WindowContext) context).dispatchConfigurationChanged(newConfig));
            }

            // Dispatch onConfigurationChanged only if there's a significant public change to
            // make it compatible with the original behavior.
            final Configuration[] sizeConfigurations = context.getResources()
                    .getSizeConfigurations();
            final SizeConfigurationBuckets buckets = sizeConfigurations != null
                    ? new SizeConfigurationBuckets(sizeConfigurations) : null;
            final int diff = diffPublicWithSizeBuckets(mConfiguration, newConfig, buckets);

            if (context instanceof WindowProviderService && diff != 0) {
                ActivityThread.currentActivityThread().getHandler().post(() ->
                        ((WindowProviderService) context).onConfigurationChanged(newConfig));
            }
            freeTextLayoutCachesIfNeeded(diff);
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
