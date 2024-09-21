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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.isFixedOrientation;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.isFixedOrientationPortrait;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.LaunchParamsUtil.applyLayoutGravity;
import static com.android.server.wm.LaunchParamsUtil.calculateLayoutBounds;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Size;
import android.view.Gravity;
import android.window.flags.DesktopModeFlags;

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
    static void updateInitialBounds(@NonNull Task task, @Nullable WindowLayout layout,
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
    @NonNull
    private static Rect calculateInitialBounds(@NonNull Task task,
            @NonNull ActivityRecord activity, @NonNull Rect stableBounds
    ) {
        final TaskInfo taskInfo = task.getTaskInfo();
        // Display bounds not taking into account insets.
        final TaskDisplayArea displayArea = task.getDisplayArea();
        final Rect screenBounds = displayArea.getBounds();
        final Size idealSize = calculateIdealSize(screenBounds, DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        if (!DesktopModeFlags.ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS.isTrue()) {
            return centerInScreen(idealSize, screenBounds);
        }
        if (activity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .hasFullscreenOverride()) {
            // If the activity has a fullscreen override applied, it should be treated as
            // resizeable and match the device orientation. Thus the ideal size can be
            // applied.
            return centerInScreen(idealSize, screenBounds);
        }
        final DesktopAppCompatAspectRatioPolicy desktopAppCompatAspectRatioPolicy =
                activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy();
        float appAspectRatio = desktopAppCompatAspectRatioPolicy.calculateAspectRatio(task);
        final float tdaWidth = stableBounds.width();
        final float tdaHeight = stableBounds.height();
        final int activityOrientation = getActivityOrientation(activity, task);
        final Size initialSize = switch (taskInfo.configuration.orientation) {
            case ORIENTATION_LANDSCAPE -> {
                // Device in landscape orientation.
                if (appAspectRatio == 0) {
                    appAspectRatio = 1;
                }
                if (canChangeAspectRatio(desktopAppCompatAspectRatioPolicy, taskInfo, task)) {
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
                yield maximizeSizeGivenAspectRatio(activityOrientation, idealSize, appAspectRatio);
            }
            case ORIENTATION_PORTRAIT -> {
                // Device in portrait orientation.
                final int customPortraitWidthForLandscapeApp = screenBounds.width()
                        - (DESKTOP_MODE_LANDSCAPE_APP_PADDING * 2);
                if (canChangeAspectRatio(desktopAppCompatAspectRatioPolicy, taskInfo, task)) {
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
     * Whether the activity's aspect ratio can be changed or if it should be maintained as if it was
     * unresizeable.
     */
    private static boolean canChangeAspectRatio(
            @NonNull DesktopAppCompatAspectRatioPolicy desktopAppCompatAspectRatioPolicy,
            @NonNull TaskInfo taskInfo, @NonNull Task task) {
        return taskInfo.isResizeable
                && !desktopAppCompatAspectRatioPolicy.hasMinAspectRatioOverride(task);
    }

    private static @ScreenOrientation int getActivityOrientation(
            @NonNull ActivityRecord activity, @NonNull Task task) {
        final int activityOrientation = activity.getOverrideOrientation();
        final DesktopAppCompatAspectRatioPolicy desktopAppCompatAspectRatioPolicy =
                activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy();
        if (desktopAppCompatAspectRatioPolicy.shouldApplyUserMinAspectRatioOverride(task)
                && (!isFixedOrientation(activityOrientation)
                    || activityOrientation == SCREEN_ORIENTATION_LOCKED)) {
            // If a user aspect ratio override should be applied, treat the activity as portrait if
            // it has not specified a fix orientation.
            return SCREEN_ORIENTATION_PORTRAIT;
        }
        return activityOrientation;
    }

    /**
     * Calculates the largest size that can fit in a given area while maintaining a specific aspect
     * ratio.
     */
    @NonNull
    private static Size maximizeSizeGivenAspectRatio(
            @ScreenOrientation int orientation,
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
     * Calculates the desired initial bounds for applications in desktop windowing. This is done as
     * a scale of the screen bounds.
     */
    @NonNull
    private static Size calculateIdealSize(@NonNull Rect screenBounds, float scale) {
        final int width = (int) (screenBounds.width() * scale);
        final int height = (int) (screenBounds.height() * scale);
        return new Size(width, height);
    }

    /**
     * Adjusts bounds to be positioned in the middle of the screen.
     */
    @NonNull
    private static Rect centerInScreen(@NonNull Size desiredSize,
            @NonNull Rect screenBounds) {
        // TODO(b/325240051): Position apps with bottom heavy offset
        final int heightOffset = (screenBounds.height() - desiredSize.getHeight()) / 2;
        final int widthOffset = (screenBounds.width() - desiredSize.getWidth()) / 2;
        final Rect resultBounds = new Rect(0, 0,
                desiredSize.getWidth(), desiredSize.getHeight());
        resultBounds.offset(screenBounds.left + widthOffset, screenBounds.top + heightOffset);
        return resultBounds;
    }
}
