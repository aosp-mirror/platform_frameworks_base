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

import static com.android.server.wm.LaunchParamsUtil.applyLayoutGravity;
import static com.android.server.wm.LaunchParamsUtil.calculateLayoutBounds;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Size;
import android.view.Gravity;

import java.util.function.Consumer;

/**
 * Calculates the value of the {@link LaunchParamsController.LaunchParams} bounds for the
 * {@link DesktopModeLaunchParamsModifier}.
 */
public final class DesktopModeBoundsCalculator {

    public static final float DESKTOP_MODE_INITIAL_BOUNDS_SCALE = SystemProperties
            .getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f;

    /**
     * Updates launch bounds for an activity with respect to its activity options, window layout,
     * android manifest and task configuration.
     */
    static void updateInitialBounds(@NonNull Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityOptions options,
            @NonNull Rect outBounds, @NonNull Consumer<String> logger) {
        // Use stable frame instead of raw frame to avoid launching freeform windows on top of
        // stable insets, which usually are system widgets such as sysbar & navbar.
        final TaskDisplayArea displayArea = task.getDisplayArea();
        final Rect screenBounds = displayArea.getBounds();
        final Rect stableBounds = new Rect();
        displayArea.getStableRect(stableBounds);
        final int desiredWidth = (int) (stableBounds.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (stableBounds.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        if (options != null && options.getLaunchBounds() != null) {
            outBounds.set(options.getLaunchBounds());
            logger.accept("inherit-from-options=" + outBounds);
        } else if (layout != null) {
            final int verticalGravity = layout.gravity & Gravity.VERTICAL_GRAVITY_MASK;
            final int horizontalGravity = layout.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (layout.hasSpecifiedSize()) {
                calculateLayoutBounds(stableBounds, layout, outBounds,
                        new Size(desiredWidth, desiredHeight));
                applyLayoutGravity(verticalGravity, horizontalGravity, outBounds,
                        stableBounds);
                logger.accept("layout specifies sizes, inheriting size and applying gravity");
            } else if (verticalGravity > 0 || horizontalGravity > 0) {
                calculateAndCentreInitialBounds(outBounds, screenBounds);
                applyLayoutGravity(verticalGravity, horizontalGravity, outBounds,
                        stableBounds);
                logger.accept("layout specifies gravity, applying desired bounds and gravity");
            }
        } else {
            calculateAndCentreInitialBounds(outBounds, screenBounds);
            logger.accept("layout not specified, applying desired bounds");
        }
    }

    /**
     * Calculates the initial height and width of a task in desktop mode and centers it within the
     * window bounds.
     */
    private static void calculateAndCentreInitialBounds(@NonNull Rect outBounds,
            @NonNull Rect screenBounds) {
        // TODO(b/319819547): Account for app constraints so apps do not become letterboxed
        // The desired dimensions that a fully resizable window should take when initially entering
        // desktop mode. Calculated as a percentage of the available display area as defined by the
        // DESKTOP_MODE_INITIAL_BOUNDS_SCALE.
        final int desiredWidth = (int) (screenBounds.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (screenBounds.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        outBounds.right = desiredWidth;
        outBounds.bottom = desiredHeight;
        outBounds.offset(screenBounds.centerX() - outBounds.centerX(),
                screenBounds.centerY() - outBounds.centerY());
    }
}
