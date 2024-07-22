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

import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.content.res.Configuration.UI_MODE_TYPE_VR_HEADSET;

import static com.android.server.wm.ActivityRecord.State.RESUMED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppCompatTaskInfo;
import android.app.CameraCompatTaskInfo;
import android.app.TaskInfo;
import android.content.res.Configuration;
import android.graphics.Rect;

import java.util.function.BooleanSupplier;

/**
 * Utilities for App Compat policies and overrides.
 */
class AppCompatUtils {

    /**
     * Lazy version of a {@link BooleanSupplier} which access an existing BooleanSupplier and
     * caches the value.
     *
     * @param supplier The BooleanSupplier to decorate.
     * @return A lazy implementation of a BooleanSupplier
     */
    @NonNull
    static BooleanSupplier asLazy(@NonNull BooleanSupplier supplier) {
        return new BooleanSupplier() {
            private boolean mRead;
            private boolean mValue;

            @Override
            public boolean getAsBoolean() {
                if (!mRead) {
                    mRead = true;
                    mValue = supplier.getAsBoolean();
                }
                return mValue;
            }
        };
    }

    /**
     * Returns the aspect ratio of the given {@code rect}.
     */
    static float computeAspectRatio(Rect rect) {
        final int width = rect.width();
        final int height = rect.height();
        if (width == 0 || height == 0) {
            return 0;
        }
        return Math.max(width, height) / (float) Math.min(width, height);
    }

    /**
     * @param config The current {@link Configuration}
     * @return {@code true} if using a VR headset.
     */
    static boolean isInVrUiMode(Configuration config) {
        return (config.uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_VR_HEADSET;
    }

    /**
     * @param activityRecord The {@link ActivityRecord} for the app package.
     * @param overrideChangeId The per-app override identifier.
     * @return {@code true} if the per-app override is enable for the given activity.
     */
    static boolean isChangeEnabled(@NonNull ActivityRecord activityRecord, long overrideChangeId) {
        return activityRecord.info.isChangeEnabled(overrideChangeId);
    }

    /**
     * Attempts to return the app bounds (bounds without insets) of the top most opaque activity. If
     * these are not available, it defaults to the bounds of the activity which include insets. In
     * the event the activity is in Size Compat Mode, the Size Compat bounds are returned instead.
     */
    @NonNull
    static Rect getAppBounds(@NonNull ActivityRecord activityRecord) {
        // TODO(b/268458693): Refactor configuration inheritance in case of translucent activities
        final Rect appBounds = activityRecord.getConfiguration().windowConfiguration.getAppBounds();
        if (appBounds == null) {
            return activityRecord.getBounds();
        }
        return activityRecord.mAppCompatController.getTransparentPolicy()
                .findOpaqueNotFinishingActivityBelow()
                .map(AppCompatUtils::getAppBounds)
                .orElseGet(() -> {
                    if (activityRecord.hasSizeCompatBounds()) {
                        return activityRecord.getScreenResolvedBounds();
                    }
                    return appBounds;
                });
    }

    static void fillAppCompatTaskInfo(@NonNull Task task, @NonNull TaskInfo info,
            @Nullable ActivityRecord top) {
        final AppCompatTaskInfo appCompatTaskInfo = info.appCompatTaskInfo;
        appCompatTaskInfo.cameraCompatTaskInfo.freeformCameraCompatMode =
                CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
        if (top == null) {
            return;
        }
        final AppCompatReachabilityOverrides reachabilityOverrides = top.mAppCompatController
                .getAppCompatReachabilityOverrides();
        final boolean isTopActivityResumed = top.getOrganizedTask() == task && top.isState(RESUMED);
        final boolean isTopActivityVisible = top.getOrganizedTask() == task && top.isVisible();
        // Whether the direct top activity is in size compat mode.
        appCompatTaskInfo.topActivityInSizeCompat = isTopActivityVisible && top.inSizeCompatMode();
        if (appCompatTaskInfo.topActivityInSizeCompat
                && top.mWmService.mAppCompatConfiguration.isTranslucentLetterboxingEnabled()) {
            // We hide the restart button in case of transparent activities.
            appCompatTaskInfo.topActivityInSizeCompat = top.fillsParent();
        }
        // Whether the direct top activity is eligible for letterbox education.
        appCompatTaskInfo.topActivityEligibleForLetterboxEducation = isTopActivityResumed
                && top.isEligibleForLetterboxEducation();
        appCompatTaskInfo.isLetterboxEducationEnabled = top.mLetterboxUiController
                .isLetterboxEducationEnabled();

        appCompatTaskInfo.isUserFullscreenOverrideEnabled = top.mAppCompatController
                .getAppCompatAspectRatioOverrides().shouldApplyUserFullscreenOverride();
        appCompatTaskInfo.isSystemFullscreenOverrideEnabled = top.mAppCompatController
                .getAppCompatAspectRatioOverrides().isSystemOverrideToFullscreenEnabled();

        appCompatTaskInfo.isFromLetterboxDoubleTap = reachabilityOverrides.isFromDoubleTap();
        final Rect bounds = top.getBounds();
        final Rect appBounds = getAppBounds(top);
        appCompatTaskInfo.topActivityLetterboxWidth = bounds.width();
        appCompatTaskInfo.topActivityLetterboxHeight = bounds.height();
        appCompatTaskInfo.topActivityLetterboxAppWidth = appBounds.width();
        appCompatTaskInfo.topActivityLetterboxAppHeight = appBounds.height();

        // We need to consider if letterboxed or pillarboxed.
        // TODO(b/336807329) Encapsulate reachability logic
        appCompatTaskInfo.isLetterboxDoubleTapEnabled = reachabilityOverrides
                .isLetterboxDoubleTapEducationEnabled();
        if (appCompatTaskInfo.isLetterboxDoubleTapEnabled) {
            if (appCompatTaskInfo.isTopActivityPillarboxed()) {
                if (reachabilityOverrides.allowHorizontalReachabilityForThinLetterbox()) {
                    // Pillarboxed.
                    appCompatTaskInfo.topActivityLetterboxHorizontalPosition =
                            reachabilityOverrides.getLetterboxPositionForHorizontalReachability();
                } else {
                    appCompatTaskInfo.isLetterboxDoubleTapEnabled = false;
                }
            } else {
                if (reachabilityOverrides.allowVerticalReachabilityForThinLetterbox()) {
                    // Letterboxed.
                    appCompatTaskInfo.topActivityLetterboxVerticalPosition =
                            reachabilityOverrides.getLetterboxPositionForVerticalReachability();
                } else {
                    appCompatTaskInfo.isLetterboxDoubleTapEnabled = false;
                }
            }
        }
        appCompatTaskInfo.topActivityEligibleForUserAspectRatioButton =
                !info.isTopActivityTransparent && !appCompatTaskInfo.topActivityInSizeCompat
                        && top.mAppCompatController.getAppCompatAspectRatioOverrides()
                            .shouldEnableUserAspectRatioSettings();
        appCompatTaskInfo.topActivityBoundsLetterboxed = top.areBoundsLetterboxed();
        appCompatTaskInfo.cameraCompatTaskInfo.freeformCameraCompatMode = top.mAppCompatController
                .getAppCompatCameraOverrides().getFreeformCameraCompatMode();
    }
}
