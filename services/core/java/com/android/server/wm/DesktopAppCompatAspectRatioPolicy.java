/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_APP_DEFAULT;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.AppCompatConfiguration.DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;

import android.annotation.NonNull;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

// TODO(b/359217664): Consider refactoring into AppCompatAspectRatioPolicy.
/**
 * Encapsulate app compat aspect ratio policy logic specific for desktop windowing initial bounds
 * calculation.
 */
public class DesktopAppCompatAspectRatioPolicy {

    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;
    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final TransparentPolicy mTransparentPolicy;

    DesktopAppCompatAspectRatioPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatOverrides appCompatOverrides,
            @NonNull TransparentPolicy transparentPolicy,
            @NonNull AppCompatConfiguration appCompatConfiguration) {
        mActivityRecord = activityRecord;
        mAppCompatOverrides = appCompatOverrides;
        mTransparentPolicy = transparentPolicy;
        mAppCompatConfiguration = appCompatConfiguration;
    }

    /**
     * Calculates the final aspect ratio of an launching activity based on the task it will be
     * launched in. Takes into account any min or max aspect ratio constraints.
     */
    float calculateAspectRatio(@NonNull Task task) {
        final float maxAspectRatio = getMaxAspectRatio();
        final float minAspectRatio = getMinAspectRatio(task);
        float desiredAspectRatio = 0;
        desiredAspectRatio = getDesiredAspectRatio(task);
        if (maxAspectRatio >= 1 && desiredAspectRatio > maxAspectRatio) {
            desiredAspectRatio = maxAspectRatio;
        } else if (minAspectRatio >= 1 && desiredAspectRatio < minAspectRatio) {
            desiredAspectRatio = minAspectRatio;
        }
        return desiredAspectRatio;
    }

    /**
     * Returns the aspect ratio desired by the system for current activity, not taking into account
     * any min or max aspect ratio constraints.
     */
    @VisibleForTesting
    float getDesiredAspectRatio(@NonNull Task task) {
        final float letterboxAspectRatioOverride = getFixedOrientationLetterboxAspectRatio(task);
        // Aspect ratio as suggested by the system. Apps requested mix/max aspect ratio will
        // be respected in #calculateAspectRatio.
        if (isDefaultMultiWindowLetterboxAspectRatioDesired(task)) {
            return DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
        } else if (letterboxAspectRatioOverride > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO) {
            return letterboxAspectRatioOverride;
        }
        return AppCompatUtils.computeAspectRatio(task.getDisplayArea().getBounds());
    }

    /**
     * Determines the letterbox aspect ratio for an application based on its orientation and
     * resizability.
     */
    private float getFixedOrientationLetterboxAspectRatio(@NonNull Task task) {
        return mActivityRecord.shouldCreateAppCompatDisplayInsets()
                ? getDefaultMinAspectRatioForUnresizableApps(task)
                : getDefaultMinAspectRatio(task);
    }

    /**
     * Calculates the aspect ratio of the available display area when an app enters split-screen on
     * a given device, taking into account any dividers and insets.
     */
    private float getSplitScreenAspectRatio(@NonNull Task task) {
        // Getting the same aspect ratio that apps get in split screen.
        final DisplayArea displayArea = task.getDisplayArea();
        final int dividerWindowWidth =
                mActivityRecord.mWmService.mContext.getResources().getDimensionPixelSize(
                        R.dimen.docked_stack_divider_thickness);
        final int dividerInsets =
                mActivityRecord.mWmService.mContext.getResources().getDimensionPixelSize(
                        R.dimen.docked_stack_divider_insets);
        final int dividerSize = dividerWindowWidth - dividerInsets * 2;
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return AppCompatUtils.computeAspectRatio(bounds);
    }


    /**
     * Returns the minimum aspect ratio for unresizable apps as determined by the system.
     */
    private float getDefaultMinAspectRatioForUnresizableApps(@NonNull Task task) {
        if (!mAppCompatConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled()) {
            return mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO
                    ? mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    : getDefaultMinAspectRatio(task);
        }

        return getSplitScreenAspectRatio(task);
    }

    /**
     * Returns the default minimum aspect ratio for apps as determined by the system.
     */
    private float getDefaultMinAspectRatio(@NonNull Task task) {
        if (!mAppCompatConfiguration.getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox()) {
            return mAppCompatConfiguration.getFixedOrientationLetterboxAspectRatio();
        }
        return getDisplayAreaMinAspectRatio(task);
    }

    /**
     * Calculates the aspect ratio of the available display area.
     */
    private float getDisplayAreaMinAspectRatio(@NonNull Task task) {
        final DisplayArea displayArea = task.getDisplayArea();
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        return AppCompatUtils.computeAspectRatio(bounds);
    }

    /**
     * Returns {@code true} if the default aspect ratio for a letterboxed app in multi-window mode
     * should be used.
     */
    private boolean isDefaultMultiWindowLetterboxAspectRatioDesired(@NonNull Task task) {
        final DisplayContent dc = task.mDisplayContent;
        final int windowingMode = task.getDisplayArea().getWindowingMode();
        return WindowConfiguration.inMultiWindowMode(windowingMode)
                && !dc.getIgnoreOrientationRequest();
    }

    /**
     * Returns the min aspect ratio of this activity.
     */
    private float getMinAspectRatio(@NonNull Task task) {
        if (mTransparentPolicy.isRunning()) {
            return mTransparentPolicy.getInheritedMinAspectRatio();
        }

        final ActivityInfo info = mActivityRecord.info;
        if (info.applicationInfo == null) {
            return info.getMinAspectRatio();
        }

        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                mAppCompatOverrides.getAppCompatAspectRatioOverrides();
        if (shouldApplyUserMinAspectRatioOverride(task)) {
            return aspectRatioOverrides.getUserMinAspectRatio();
        }

        final DisplayContent dc = task.mDisplayContent;
        final boolean shouldOverrideMinAspectRatioForCamera = dc != null
                && dc.mAppCompatCameraPolicy.shouldOverrideMinAspectRatioForCamera(mActivityRecord);
        if (!aspectRatioOverrides.shouldOverrideMinAspectRatio()
                && !shouldOverrideMinAspectRatioForCamera) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY)
                && !ActivityInfo.isFixedOrientationPortrait(
                        mActivityRecord.getOverrideOrientation())) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN)
                && isFullscreenPortrait(task)) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN)) {
            return Math.max(aspectRatioOverrides.getSplitScreenAspectRatio(),
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_LARGE)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_MEDIUM)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                    info.getMinAspectRatio());
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_SMALL)) {
            return Math.max(ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE,
                    info.getMinAspectRatio());
        }
        return info.getMinAspectRatio();
    }

    /**
     * Returns the max aspect ratio of this activity.
     */
    private float getMaxAspectRatio() {
        if (mTransparentPolicy.isRunning()) {
            return mTransparentPolicy.getInheritedMaxAspectRatio();
        }
        return mActivityRecord.info.getMaxAspectRatio();
    }

    /**
     * Whether an applications minimum aspect ratio has been overridden.
     */
    boolean hasMinAspectRatioOverride(@NonNull Task task) {
        return mActivityRecord.info.getMinAspectRatio() < getMinAspectRatio(task);
    }

    /**
     * Whether we should apply the user aspect ratio override to the min aspect ratio for the
     * current app.
     */
    boolean shouldApplyUserMinAspectRatioOverride(@NonNull Task task) {
        if (!shouldEnableUserAspectRatioSettings(task)) {
            return false;
        }

        final int userAspectRatioCode = mAppCompatOverrides.getAppCompatAspectRatioOverrides()
                .getUserMinAspectRatioOverrideCode();

        return userAspectRatioCode != USER_MIN_ASPECT_RATIO_UNSET
                && userAspectRatioCode != USER_MIN_ASPECT_RATIO_APP_DEFAULT
                && userAspectRatioCode != USER_MIN_ASPECT_RATIO_FULLSCREEN;
    }

    /**
     * Whether we should enable users to resize the current app.
     */
    private boolean shouldEnableUserAspectRatioSettings(@NonNull Task task) {
        // We use mBooleanPropertyAllowUserAspectRatioOverride to allow apps to opt-out which has
        // effect only if explicitly false. If mBooleanPropertyAllowUserAspectRatioOverride is null,
        // the current app doesn't opt-out so the first part of the predicate is true.
        return mAppCompatOverrides.getAppCompatAspectRatioOverrides()
                    .getAllowUserAspectRatioOverridePropertyValue()
                && mAppCompatConfiguration.isUserAppAspectRatioSettingsEnabled()
                && task.mDisplayContent.getIgnoreOrientationRequest();
    }

    /**
     * Returns {@code true} if the task window is portrait and fullscreen.
     */
    private boolean isFullscreenPortrait(@NonNull Task task) {
        return task.getConfiguration().orientation == ORIENTATION_PORTRAIT
                && task.getWindowConfiguration().getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }
}
