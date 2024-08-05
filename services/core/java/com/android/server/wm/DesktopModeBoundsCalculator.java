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

import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.isFixedOrientationPortrait;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.AppCompatConfiguration.DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.server.wm.AppCompatUtils.computeAspectRatio;
import static com.android.server.wm.LaunchParamsUtil.applyLayoutGravity;
import static com.android.server.wm.LaunchParamsUtil.calculateLayoutBounds;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.AppCompatTaskInfo;
import android.app.TaskInfo;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Size;
import android.view.Gravity;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.utils.DesktopModeFlagsUtil;

import java.util.function.Consumer;

/**
 * Calculates the value of the {@link LaunchParamsController.LaunchParams} bounds for the
 * {@link DesktopModeLaunchParamsModifier}.
 */
public final class DesktopModeBoundsCalculator {

    public static final float DESKTOP_MODE_INITIAL_BOUNDS_SCALE = SystemProperties
            .getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f;
    public static final int DESKTOP_MODE_LANDSCAPE_APP_PADDING = SystemProperties
            .getInt("persist.wm.debug.desktop_mode_landscape_app_padding", 25);

    /**
     * Updates launch bounds for an activity with respect to its activity options, window layout,
     * android manifest and task configuration.
     */
    static void updateInitialBounds(@NonNull Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityOptions options,
            @NonNull Rect outBounds, @NonNull Consumer<String> logger) {
        // Use stable frame instead of raw frame to avoid launching freeform windows on top of
        // stable insets, which usually are system widgets such as sysbar & navbar.
        final Rect stableBounds = new Rect();
        task.getDisplayArea().getStableRect(stableBounds);

        if (options != null && options.getLaunchBounds() != null) {
            outBounds.set(options.getLaunchBounds());
            logger.accept("inherit-from-options=" + outBounds);
        } else if (layout != null) {
            final int verticalGravity = layout.gravity & Gravity.VERTICAL_GRAVITY_MASK;
            final int horizontalGravity = layout.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (layout.hasSpecifiedSize()) {
                calculateLayoutBounds(stableBounds, layout, outBounds,
                        calculateIdealSize(stableBounds, DESKTOP_MODE_INITIAL_BOUNDS_SCALE));
                applyLayoutGravity(verticalGravity, horizontalGravity, outBounds,
                        stableBounds);
                logger.accept("layout specifies sizes, inheriting size and applying gravity");
            } else if (verticalGravity > 0 || horizontalGravity > 0) {
                outBounds.set(calculateInitialBounds(task, activity, stableBounds));
                applyLayoutGravity(verticalGravity, horizontalGravity, outBounds,
                        stableBounds);
                logger.accept("layout specifies gravity, applying desired bounds and gravity");
            }
        } else {
            outBounds.set(calculateInitialBounds(task, activity, stableBounds));
            logger.accept("layout not specified, applying desired bounds");
        }
    }

    /**
     * Calculates the initial bounds required for an application to fill a scale of the display
     * bounds without any letterboxing. This is done by taking into account the applications
     * fullscreen size, aspect ratio, orientation and resizability to calculate an area this is
     * compatible with the applications previous configuration.
     */
    private static @NonNull Rect calculateInitialBounds(@NonNull Task task,
            @NonNull ActivityRecord activity, @NonNull Rect stableBounds
    ) {
        final TaskInfo taskInfo = task.getTaskInfo();
        // Display bounds not taking into account insets.
        final TaskDisplayArea displayArea = task.getDisplayArea();
        final Rect screenBounds = displayArea.getBounds();
        final Size idealSize = calculateIdealSize(screenBounds, DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        if (!DesktopModeFlagsUtil.DYNAMIC_INITIAL_BOUNDS.isEnabled(activity.mWmService.mContext)) {
            return centerInScreen(idealSize, screenBounds);
        }
        // TODO(b/353457301): Replace with app compat aspect ratio method when refactoring complete.
        float appAspectRatio = calculateAspectRatio(task, activity);
        final float tdaWidth = stableBounds.width();
        final float tdaHeight = stableBounds.height();
        final int activityOrientation = activity.getOverrideOrientation();
        final Size initialSize = switch (taskInfo.configuration.orientation) {
            case ORIENTATION_LANDSCAPE -> {
                // Device in landscape orientation.
                if (appAspectRatio == 0) {
                    appAspectRatio = 1;
                }
                if (taskInfo.isResizeable) {
                    if (isFixedOrientationPortrait(activityOrientation)) {
                        // For portrait resizeable activities, respect apps fullscreen width but
                        // apply ideal size height.
                        yield new Size((int) ((tdaHeight / appAspectRatio) + 0.5f),
                                idealSize.getHeight());
                    }
                    // For landscape resizeable activities, simply apply ideal size.
                    yield idealSize;
                }
                // If activity is unresizeable, regardless of orientation, calculate maximum size
                // (within the ideal size) maintaining original aspect ratio.
                yield maximizeSizeGivenAspectRatio(
                        activity.getOverrideOrientation(), idealSize, appAspectRatio);
            }
            case ORIENTATION_PORTRAIT -> {
                // Device in portrait orientation.
                final int customPortraitWidthForLandscapeApp = screenBounds.width()
                        - (DESKTOP_MODE_LANDSCAPE_APP_PADDING * 2);
                if (taskInfo.isResizeable) {
                    if (isFixedOrientationLandscape(activityOrientation)) {
                        if (appAspectRatio == 0) {
                            appAspectRatio = tdaWidth / (tdaWidth - 1);
                        }
                        // For landscape resizeable activities, respect apps fullscreen height and
                        // apply custom app width.
                        yield new Size(customPortraitWidthForLandscapeApp,
                                (int) ((tdaWidth / appAspectRatio) + 0.5f));
                    }
                    // For portrait resizeable activities, simply apply ideal size.
                    yield idealSize;
                }
                if (appAspectRatio == 0) {
                    appAspectRatio = 1;
                }
                if (isFixedOrientationLandscape(activityOrientation)) {
                    // For landscape unresizeable activities, apply custom app width to ideal size
                    // and calculate maximum size with this area while maintaining original aspect
                    // ratio.
                    yield maximizeSizeGivenAspectRatio(activityOrientation,
                            new Size(customPortraitWidthForLandscapeApp, idealSize.getHeight()),
                            appAspectRatio);
                }
                // For portrait unresizeable activities, calculate maximum size (within the ideal
                // size) maintaining original aspect ratio.
                yield maximizeSizeGivenAspectRatio(activityOrientation, idealSize, appAspectRatio);
            }
            default -> idealSize;
        };
        return centerInScreen(initialSize, screenBounds);
    }

    /**
     * Calculates the largest size that can fit in a given area while maintaining a specific aspect
     * ratio.
     */
    private static @NonNull Size maximizeSizeGivenAspectRatio(
            @ActivityInfo.ScreenOrientation int orientation,
            @NonNull Size targetArea,
            float aspectRatio
    ) {
        final int targetHeight = targetArea.getHeight();
        final int targetWidth = targetArea.getWidth();
        final int finalHeight;
        final int finalWidth;
        if (isFixedOrientationPortrait(orientation)) {
            // Portrait activity.
            // Calculate required width given ideal height and aspect ratio.
            int tempWidth = (int) (targetHeight / aspectRatio);
            if (tempWidth <= targetWidth) {
                // If the calculated width does not exceed the ideal width, overall size is within
                // ideal size and can be applied.
                finalHeight = targetHeight;
                finalWidth = tempWidth;
            } else {
                // Applying target height cause overall size to exceed ideal size when maintain
                // aspect ratio. Instead apply ideal width and calculate required height to respect
                // aspect ratio.
                finalWidth = targetWidth;
                finalHeight = (int) (finalWidth * aspectRatio);
            }
        } else {
            // Landscape activity.
            // Calculate required width given ideal height and aspect ratio.
            int tempWidth = (int) (targetHeight * aspectRatio);
            if (tempWidth <= targetWidth) {
                // If the calculated width does not exceed the ideal width, overall size is within
                // ideal size and can be applied.
                finalHeight = targetHeight;
                finalWidth = tempWidth;
            } else {
                // Applying target height cause overall size to exceed ideal size when maintain
                // aspect ratio. Instead apply ideal width and calculate required height to respect
                // aspect ratio.
                finalWidth = targetWidth;
                finalHeight = (int) (finalWidth / aspectRatio);
            }
        }
        return new Size(finalWidth, finalHeight);
    }

    /**
     * Calculates the aspect ratio of an activity from its fullscreen bounds.
     */
    @VisibleForTesting
    static float calculateAspectRatio(@NonNull Task task, @NonNull ActivityRecord activity) {
        final TaskInfo taskInfo = task.getTaskInfo();
        final float fullscreenWidth = task.getDisplayArea().getBounds().width();
        final float fullscreenHeight = task.getDisplayArea().getBounds().height();
        final float maxAspectRatio = activity.getMaxAspectRatio();
        final float minAspectRatio = activity.getMinAspectRatio();
        float desiredAspectRatio = 0;
        if (taskInfo.isRunning) {
            final AppCompatTaskInfo appCompatTaskInfo =  taskInfo.appCompatTaskInfo;
            final int appLetterboxWidth =
                    taskInfo.appCompatTaskInfo.topActivityLetterboxAppWidth;
            final int appLetterboxHeight =
                    taskInfo.appCompatTaskInfo.topActivityLetterboxAppHeight;
            if (appCompatTaskInfo.isTopActivityLetterboxed()) {
                desiredAspectRatio = (float) Math.max(appLetterboxWidth, appLetterboxHeight)
                        / Math.min(appLetterboxWidth, appLetterboxHeight);
            } else {
                desiredAspectRatio = Math.max(fullscreenHeight, fullscreenWidth)
                        / Math.min(fullscreenHeight, fullscreenWidth);
            }
        } else {
            final float letterboxAspectRatioOverride =
                    getFixedOrientationLetterboxAspectRatio(activity, task);
            if (!task.mDisplayContent.getIgnoreOrientationRequest()) {
                desiredAspectRatio = DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;
            } else if (letterboxAspectRatioOverride
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO) {
                desiredAspectRatio = letterboxAspectRatioOverride;
            }
        }
        // If the activity matches display orientation, the display aspect ratio should be used
        if (activityMatchesDisplayOrientation(
                taskInfo.configuration.orientation,
                activity.getOverrideOrientation())) {
            desiredAspectRatio = Math.max(fullscreenWidth, fullscreenHeight)
                    / Math.min(fullscreenWidth, fullscreenHeight);
        }
        if (maxAspectRatio >= 1 && desiredAspectRatio > maxAspectRatio) {
            desiredAspectRatio = maxAspectRatio;
        } else if (minAspectRatio >= 1 && desiredAspectRatio < minAspectRatio) {
            desiredAspectRatio = minAspectRatio;
        }
        return desiredAspectRatio;
    }

    private static boolean activityMatchesDisplayOrientation(
            @Configuration.Orientation int deviceOrientation,
            @ActivityInfo.ScreenOrientation int activityOrientation) {
        if (deviceOrientation == ORIENTATION_PORTRAIT) {
            return isFixedOrientationPortrait(activityOrientation);
        }
        return isFixedOrientationLandscape(activityOrientation);
    }

    /**
     * Calculates the desired initial bounds for applications in desktop windowing. This is done as
     * a scale of the screen bounds.
     */
    private static @NonNull Size calculateIdealSize(@NonNull Rect screenBounds, float scale) {
        final int width = (int) (screenBounds.width() * scale);
        final int height = (int) (screenBounds.height() * scale);
        return new Size(width, height);
    }

    /**
     * Adjusts bounds to be positioned in the middle of the screen.
     */
    private static @NonNull Rect centerInScreen(@NonNull Size desiredSize,
            @NonNull Rect screenBounds) {
        // TODO(b/325240051): Position apps with bottom heavy offset
        final int heightOffset = (screenBounds.height() - desiredSize.getHeight()) / 2;
        final int widthOffset = (screenBounds.width() - desiredSize.getWidth()) / 2;
        final Rect resultBounds = new Rect(0, 0,
                desiredSize.getWidth(), desiredSize.getHeight());
        resultBounds.offset(screenBounds.left + widthOffset, screenBounds.top + heightOffset);
        return resultBounds;
    }

    private static float getFixedOrientationLetterboxAspectRatio(@NonNull ActivityRecord activity,
            @NonNull Task task) {
        return activity.shouldCreateCompatDisplayInsets()
                ? getDefaultMinAspectRatioForUnresizableApps(activity, task)
                : activity.mAppCompatController.getAppCompatAspectRatioOverrides()
                        .getDefaultMinAspectRatio();
    }

    private static float getDefaultMinAspectRatioForUnresizableApps(
            @NonNull ActivityRecord activity,
            @NonNull Task task) {
        final AppCompatAspectRatioOverrides appCompatAspectRatioOverrides =
                activity.mAppCompatController.getAppCompatAspectRatioOverrides();
        if (appCompatAspectRatioOverrides.isSplitScreenAspectRatioForUnresizableAppsEnabled()) {
            // Default letterbox aspect ratio for unresizable apps.
            return getSplitScreenAspectRatio(activity, task);
        }

        if (appCompatAspectRatioOverrides.getDefaultMinAspectRatioForUnresizableAppsFromConfig()
                > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO) {
            return appCompatAspectRatioOverrides
                    .getDefaultMinAspectRatioForUnresizableAppsFromConfig();
        }

        return appCompatAspectRatioOverrides.getDefaultMinAspectRatio();
    }

    /**
     * Calculates the aspect ratio of the available display area when an app enters split-screen on
     * a given device, taking into account any dividers and insets.
     */
    private static float getSplitScreenAspectRatio(@NonNull ActivityRecord activity,
            @NonNull Task task) {
        final int dividerWindowWidth =
                activity.mWmService.mContext.getResources().getDimensionPixelSize(
                        R.dimen.docked_stack_divider_thickness);
        final int dividerInsets =
                activity.mWmService.mContext.getResources().getDimensionPixelSize(
                        R.dimen.docked_stack_divider_insets);
        final int dividerSize = dividerWindowWidth - dividerInsets * 2;
        final Rect bounds = new Rect(0, 0,
                task.mDisplayContent.getDisplayInfo().appWidth,
                task.mDisplayContent.getDisplayInfo().appHeight);
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return computeAspectRatio(bounds);
    }
}
