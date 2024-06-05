/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration.Orientation;
import android.view.Surface;
import android.view.WindowInsets.Type;

/**
 * Policy to decide whether to enforce screen rotation lock for optimisation of the screen rotation
 * user experience for immersive applications for compatibility when ignoring orientation request.
 *
 * <p>This is needed because immersive apps, such as games, are often not optimized for all
 * orientations and can have a poor UX when rotated (e.g., state loss or entering size-compat mode).
 * Additionally, some games rely on sensors for the gameplay so users can trigger such rotations
 * accidentally when auto rotation is on.
 */
final class DisplayRotationImmersiveAppCompatPolicy {

    @Nullable
    static DisplayRotationImmersiveAppCompatPolicy createIfNeeded(
            @NonNull final LetterboxConfiguration letterboxConfiguration,
            @NonNull final DisplayRotation displayRotation,
            @NonNull final DisplayContent displayContent) {
        if (!letterboxConfiguration
                .isDisplayRotationImmersiveAppCompatPolicyEnabledAtBuildTime()) {
            return null;
        }

        return new DisplayRotationImmersiveAppCompatPolicy(
                letterboxConfiguration, displayRotation, displayContent);
    }

    private final DisplayRotation mDisplayRotation;
    private final LetterboxConfiguration mLetterboxConfiguration;
    private final DisplayContent mDisplayContent;

    private DisplayRotationImmersiveAppCompatPolicy(
            @NonNull final LetterboxConfiguration letterboxConfiguration,
            @NonNull final DisplayRotation displayRotation,
            @NonNull final DisplayContent displayContent) {
        mDisplayRotation = displayRotation;
        mLetterboxConfiguration = letterboxConfiguration;
        mDisplayContent = displayContent;
    }

    /**
     * Decides whether it is necessary to lock screen rotation, preventing auto rotation, based on
     * the top activity configuration and proposed screen rotation.
     *
     * <p>This is needed because immersive apps, such as games, are often not optimized for all
     * orientations and can have a poor UX when rotated. Additionally, some games rely on sensors
     * for the gameplay so users can trigger such rotations accidentally when auto rotation is on.
     *
     * <p>Screen rotation is locked when the following conditions are met:
     * <ul>
     *   <li>Top activity requests to hide status and navigation bars
     *   <li>Top activity is fullscreen and in optimal orientation (without letterboxing)
     *   <li>Rotation will lead to letterboxing due to fixed orientation.
     *   <li>{@link DisplayContent#getIgnoreOrientationRequest} is {@code true}
     *   <li>This policy is enabled on the device, for details see
     *   {@link LetterboxConfiguration#isDisplayRotationImmersiveAppCompatPolicyEnabled}
     * </ul>
     *
     * @param proposedRotation new proposed {@link Surface.Rotation} for the screen.
     * @return {@code true}, if there is a need to lock screen rotation, {@code false} otherwise.
     */
    boolean isRotationLockEnforced(@Surface.Rotation final int proposedRotation) {
        if (!mLetterboxConfiguration.isDisplayRotationImmersiveAppCompatPolicyEnabled()) {
            return false;
        }
        synchronized (mDisplayContent.mWmService.mGlobalLock) {
            return isRotationLockEnforcedLocked(proposedRotation);
        }
    }

    private boolean isRotationLockEnforcedLocked(@Surface.Rotation final int proposedRotation) {
        if (!mDisplayContent.getIgnoreOrientationRequest()) {
            return false;
        }

        final ActivityRecord activityRecord = mDisplayContent.topRunningActivity();
        if (activityRecord == null) {
            return false;
        }

        // Don't lock screen rotation if an activity hasn't requested to hide system bars.
        if (!hasRequestedToHideStatusAndNavBars(activityRecord)) {
            return false;
        }

        // Don't lock screen rotation if activity is not in fullscreen. Checking windowing mode
        // for a task rather than an activity to exclude activity embedding scenario.
        if (activityRecord.getTask() == null
                || activityRecord.getTask().getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
            return false;
        }

        // Don't lock screen rotation if activity is letterboxed.
        if (activityRecord.areBoundsLetterboxed()) {
            return false;
        }

        if (activityRecord.getRequestedConfigurationOrientation() == ORIENTATION_UNDEFINED) {
            return false;
        }

        // Lock screen rotation only if, after rotation the activity's orientation won't match
        // the screen orientation, forcing the activity to enter letterbox mode after rotation.
        return activityRecord.getRequestedConfigurationOrientation()
                != surfaceRotationToConfigurationOrientation(proposedRotation);
    }

    /**
     * Checks whether activity has requested to hide status and navigation bars.
     */
    private boolean hasRequestedToHideStatusAndNavBars(@NonNull ActivityRecord activity) {
        WindowState mainWindow = activity.findMainWindow();
        if (mainWindow == null) {
            return false;
        }
        return (mainWindow.getRequestedVisibleTypes()
                & (Type.statusBars() | Type.navigationBars())) == 0;
    }

    @Orientation
    private int surfaceRotationToConfigurationOrientation(@Surface.Rotation final int rotation) {
        if (mDisplayRotation.isAnyPortrait(rotation)) {
            return ORIENTATION_PORTRAIT;
        } else if (mDisplayRotation.isLandscapeOrSeascape(rotation)) {
            return ORIENTATION_LANDSCAPE;
        } else {
            return ORIENTATION_UNDEFINED;
        }
    }
}
