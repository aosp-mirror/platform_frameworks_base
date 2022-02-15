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

package android.window;

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ResourcesManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Display;
import android.view.WindowManager;

/**
 * A helper class to maintain {@link android.content.res.Configuration} related methods used both
 * in {@link android.app.Activity} and {@link WindowContext}.
 *
 * @hide
 */
public class ConfigurationHelper {
    private ConfigurationHelper() {}

    /** Ask text layout engine to free its caches if there is a locale change. */
    public static void freeTextLayoutCachesIfNeeded(int configDiff) {
        if ((configDiff & ActivityInfo.CONFIG_LOCALE) != 0) {
            Canvas.freeTextLayoutCaches();
        }
    }

    /**
     * Returns {@code true} if the {@link android.content.res.Resources} associated with
     * a {@code token} needs to be updated.
     *
     * @param token A {@link Context#getActivityToken() activity token} or
     * {@link Context#getWindowContextToken() window context token}
     * @param config The original {@link Configuration}
     * @param newConfig The updated Configuration
     * @param displayChanged a flag to indicate there's a display change
     * @param configChanged a flag to indicate there's a Configuration change.
     *
     * @see ResourcesManager#updateResourcesForActivity(IBinder, Configuration, int)
     */
    public static boolean shouldUpdateResources(IBinder token, @Nullable Configuration config,
            @NonNull Configuration newConfig, @NonNull Configuration overrideConfig,
            boolean displayChanged, @Nullable Boolean configChanged) {
        // The configuration has not yet been initialized. We should update it.
        if (config == null) {
            return true;
        }
        // If the token associated context is moved to another display, we should update the
        // ResourcesKey.
        if (displayChanged) {
            return true;
        }
        // If the new config is the same as the config this Activity is already running with and
        // the override config also didn't change, then don't update the Resources
        if (!ResourcesManager.getInstance().isSameResourcesOverrideConfig(token, overrideConfig)) {
            return true;
        }
        // If there's a update on WindowConfiguration#mBounds or maxBounds, we should update the
        // Resources to make WindowMetrics API report the updated result.
        if (shouldUpdateWindowMetricsBounds(config, newConfig)) {
            return true;
        }
        // If the display rotation has changed, we also need to update resources.
        if (isDisplayRotationChanged(config, newConfig)) {
            return true;
        }
        return configChanged == null ? config.diff(newConfig) != 0 : configChanged;
    }

    /**
     * Returns {@code true} if {@code displayId} is different from {@code newDisplayId}.
     * Note that {@link Display#INVALID_DISPLAY} means no difference.
     */
    public static boolean isDifferentDisplay(int displayId, int newDisplayId) {
        return newDisplayId != INVALID_DISPLAY && displayId != newDisplayId;
    }

    // TODO(b/173090263): Remove this method after the improvement of AssetManager and ResourcesImpl
    // constructions.
    /**
     * Returns {@code true} if the metrics reported by {@link android.view.WindowMetrics} APIs
     * should be updated.
     *
     * @see WindowManager#getCurrentWindowMetrics()
     * @see WindowManager#getMaximumWindowMetrics()
     */
    private static boolean shouldUpdateWindowMetricsBounds(@NonNull Configuration currentConfig,
            @NonNull Configuration newConfig) {
        final Rect currentBounds = currentConfig.windowConfiguration.getBounds();
        final Rect newBounds = newConfig.windowConfiguration.getBounds();

        final Rect currentMaxBounds = currentConfig.windowConfiguration.getMaxBounds();
        final Rect newMaxBounds = newConfig.windowConfiguration.getMaxBounds();

        return !currentBounds.equals(newBounds) || !currentMaxBounds.equals(newMaxBounds);
    }

    private static boolean isDisplayRotationChanged(@NonNull Configuration config,
            @NonNull Configuration newConfig) {
        final int origRot = config.windowConfiguration.getDisplayRotation();
        final int newRot = newConfig.windowConfiguration.getDisplayRotation();
        if (newRot == WindowConfiguration.ROTATION_UNDEFINED
                || origRot == WindowConfiguration.ROTATION_UNDEFINED) {
            return false;
        }
        return origRot != newRot;
    }
}
