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
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowInsets;

import java.util.function.BooleanSupplier;

/**
 * Utilities for App Compat policies and overrides.
 */
final class AppCompatUtils {

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
    static float computeAspectRatio(@NonNull Rect rect) {
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
        clearAppCompatTaskInfo(appCompatTaskInfo);

        if (top == null) {
            return;
        }
        final AppCompatReachabilityOverrides reachabilityOverrides = top.mAppCompatController
                .getAppCompatReachabilityOverrides();
        final boolean isTopActivityResumed = top.getOrganizedTask() == task && top.isState(RESUMED);
        final boolean isTopActivityVisible = top.getOrganizedTask() == task && top.isVisible();
        // Whether the direct top activity is in size compat mode.
        appCompatTaskInfo.setTopActivityInSizeCompat(
                isTopActivityVisible && top.inSizeCompatMode());
        if (appCompatTaskInfo.isTopActivityInSizeCompat()
                && top.mWmService.mAppCompatConfiguration.isTranslucentLetterboxingEnabled()) {
            // We hide the restart button in case of transparent activities.
            appCompatTaskInfo.setTopActivityInSizeCompat(top.fillsParent());
        }
        // Whether the direct top activity is eligible for letterbox education.
        appCompatTaskInfo.setEligibleForLetterboxEducation(
                isTopActivityResumed && top.isEligibleForLetterboxEducation());
        appCompatTaskInfo.setLetterboxEducationEnabled(top.mAppCompatController
                .getAppCompatLetterboxOverrides().isLetterboxEducationEnabled());

        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                top.mAppCompatController.getAppCompatAspectRatioOverrides();
        appCompatTaskInfo.setUserFullscreenOverrideEnabled(
                aspectRatioOverrides.shouldApplyUserFullscreenOverride());
        appCompatTaskInfo.setSystemFullscreenOverrideEnabled(
                aspectRatioOverrides.isSystemOverrideToFullscreenEnabled());

        appCompatTaskInfo.setIsFromLetterboxDoubleTap(reachabilityOverrides.isFromDoubleTap());

        final Rect bounds = top.getBounds();
        final Rect appBounds = getAppBounds(top);
        appCompatTaskInfo.topActivityLetterboxWidth = bounds.width();
        appCompatTaskInfo.topActivityLetterboxHeight = bounds.height();
        appCompatTaskInfo.topActivityLetterboxAppWidth = appBounds.width();
        appCompatTaskInfo.topActivityLetterboxAppHeight = appBounds.height();

        // We need to consider if letterboxed or pillarboxed.
        // TODO(b/336807329) Encapsulate reachability logic
        appCompatTaskInfo.setLetterboxDoubleTapEnabled(reachabilityOverrides
                .isLetterboxDoubleTapEducationEnabled());
        if (appCompatTaskInfo.isLetterboxDoubleTapEnabled()) {
            if (appCompatTaskInfo.isTopActivityPillarboxed()) {
                if (reachabilityOverrides.allowHorizontalReachabilityForThinLetterbox()) {
                    // Pillarboxed.
                    appCompatTaskInfo.topActivityLetterboxHorizontalPosition =
                            reachabilityOverrides.getLetterboxPositionForHorizontalReachability();
                } else {
                    appCompatTaskInfo.setLetterboxDoubleTapEnabled(false);
                }
            } else {
                if (reachabilityOverrides.allowVerticalReachabilityForThinLetterbox()) {
                    // Letterboxed.
                    appCompatTaskInfo.topActivityLetterboxVerticalPosition =
                            reachabilityOverrides.getLetterboxPositionForVerticalReachability();
                } else {
                    appCompatTaskInfo.setLetterboxDoubleTapEnabled(false);
                }
            }
        }
        final boolean eligibleForAspectRatioButton =
                !info.isTopActivityTransparent && !appCompatTaskInfo.isTopActivityInSizeCompat()
                        && aspectRatioOverrides.shouldEnableUserAspectRatioSettings();
        appCompatTaskInfo.setEligibleForUserAspectRatioButton(eligibleForAspectRatioButton);
        appCompatTaskInfo.setTopActivityLetterboxed(top.areBoundsLetterboxed());
        appCompatTaskInfo.cameraCompatTaskInfo.freeformCameraCompatMode = top.mAppCompatController
                .getAppCompatCameraOverrides().getFreeformCameraCompatMode();
        appCompatTaskInfo.setHasMinAspectRatioOverride(top.mAppCompatController
                .getDesktopAppCompatAspectRatioPolicy().hasMinAspectRatioOverride(task));
    }

    /**
     * Returns a string representing the reason for letterboxing. This method assumes the activity
     * is letterboxed.
     * @param activityRecord The {@link ActivityRecord} for the letterboxed activity.
     * @param mainWin   The {@link WindowState} used to letterboxing.
     */
    @NonNull
    static String getLetterboxReasonString(@NonNull ActivityRecord activityRecord,
            @NonNull WindowState mainWin) {
        if (activityRecord.inSizeCompatMode()) {
            return "SIZE_COMPAT_MODE";
        }
        final AppCompatAspectRatioPolicy aspectRatioPolicy = activityRecord.mAppCompatController
                .getAppCompatAspectRatioPolicy();
        if (aspectRatioPolicy.isLetterboxedForFixedOrientationAndAspectRatio()) {
            return "FIXED_ORIENTATION";
        }
        if (mainWin.isLetterboxedForDisplayCutout()) {
            return "DISPLAY_CUTOUT";
        }
        if (aspectRatioPolicy.isLetterboxedForAspectRatioOnly()) {
            return "ASPECT_RATIO";
        }
        return "UNKNOWN_REASON";
    }

    /**
     * Returns the taskbar in case it is visible and expanded in height, otherwise returns null.
     */
    @Nullable
    static InsetsSource getExpandedTaskbarOrNull(@NonNull final WindowState mainWindow) {
        final InsetsState state = mainWindow.getInsetsState();
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if (source.getType() == WindowInsets.Type.navigationBars()
                    && source.hasFlags(InsetsSource.FLAG_INSETS_ROUNDED_CORNER)
                    && source.isVisible()) {
                return source;
            }
        }
        return null;
    }

    static void adjustBoundsForTaskbar(@NonNull final WindowState mainWindow,
            @NonNull final Rect bounds) {
        // Rounded corners should be displayed above the taskbar. When taskbar is hidden,
        // an insets frame is equal to a navigation bar which shouldn't affect position of
        // rounded corners since apps are expected to handle navigation bar inset.
        // This condition checks whether the taskbar is visible.
        // Do not crop the taskbar inset if the window is in immersive mode - the user can
        // swipe to show/hide the taskbar as an overlay.
        // Adjust the bounds only in case there is an expanded taskbar,
        // otherwise the rounded corners will be shown behind the navbar.
        final InsetsSource expandedTaskbarOrNull = getExpandedTaskbarOrNull(mainWindow);
        if (expandedTaskbarOrNull != null) {
            // Rounded corners should be displayed above the expanded taskbar.
            bounds.bottom = Math.min(bounds.bottom, expandedTaskbarOrNull.getFrame().top);
        }
    }

    static void offsetBounds(@NonNull Configuration inOutConfig, int offsetX, int offsetY) {
        inOutConfig.windowConfiguration.getBounds().offset(offsetX, offsetY);
        inOutConfig.windowConfiguration.getAppBounds().offset(offsetX, offsetY);
    }

    private static void clearAppCompatTaskInfo(@NonNull AppCompatTaskInfo info) {
        info.topActivityLetterboxVerticalPosition = TaskInfo.PROPERTY_VALUE_UNSET;
        info.topActivityLetterboxHorizontalPosition = TaskInfo.PROPERTY_VALUE_UNSET;
        info.topActivityLetterboxWidth = TaskInfo.PROPERTY_VALUE_UNSET;
        info.topActivityLetterboxHeight = TaskInfo.PROPERTY_VALUE_UNSET;
        info.topActivityLetterboxAppHeight = TaskInfo.PROPERTY_VALUE_UNSET;
        info.topActivityLetterboxAppWidth = TaskInfo.PROPERTY_VALUE_UNSET;
        info.cameraCompatTaskInfo.freeformCameraCompatMode =
                CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
        info.clearTopActivityFlags();
    }
}
