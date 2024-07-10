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
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;

/**
 * Encapsulate app compat policy logic related to aspect ratio.
 */
class AppCompatAspectRatioPolicy {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final TransparentPolicy mTransparentPolicy;
    @NonNull
    private final AppCompatOrientationPolicy mAppCompatOrientationPolicy;
    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;

    AppCompatAspectRatioPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull TransparentPolicy transparentPolicy,
            @NonNull AppCompatOrientationPolicy orientationPolicy,
            @NonNull AppCompatOverrides appCompatOverrides) {
        mActivityRecord = activityRecord;
        mTransparentPolicy = transparentPolicy;
        mAppCompatOrientationPolicy = orientationPolicy;
        mAppCompatOverrides = appCompatOverrides;
    }

    /**
     * Returns the min aspect ratio of this activity.
     */
    float getMinAspectRatio() {
        if (mTransparentPolicy.isRunning()) {
            return mTransparentPolicy.getInheritedMinAspectRatio();
        }
        final ActivityInfo info = mActivityRecord.info;
        if (info.applicationInfo == null) {
            return info.getMinAspectRatio();
        }
        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                mAppCompatOverrides.getAppCompatAspectRatioOverrides();
        if (aspectRatioOverrides.shouldApplyUserMinAspectRatioOverride()) {
            return aspectRatioOverrides.getUserMinAspectRatio();
        }
        if (!aspectRatioOverrides.shouldOverrideMinAspectRatio()
                && !mAppCompatOverrides.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera()) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY)
                && !ActivityInfo.isFixedOrientationPortrait(
                    mActivityRecord.getOverrideOrientation())) {
            return info.getMinAspectRatio();
        }

        if (info.isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN)
                && isParentFullscreenPortrait()) {
            // We are using the parent configuration here as this is the most recent one that gets
            // passed to onConfigurationChanged when a relevant change takes place
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

    private boolean isParentFullscreenPortrait() {
        final WindowContainer<?> parent = mActivityRecord.getParent();
        return parent != null
                && parent.getConfiguration().orientation == ORIENTATION_PORTRAIT
                && parent.getWindowConfiguration().getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }
}
