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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_FREE;

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
            @NonNull final AppCompatConfiguration appCompatConfiguration,
            @NonNull final DisplayRotation displayRotation,
            @NonNull final DisplayContent displayContent) {
        if (!appCompatConfiguration
                .isDisplayRotationImmersiveAppCompatPolicyEnabledAtBuildTime()) {
            return null;
        }

        return new DisplayRotationImmersiveAppCompatPolicy(
                appCompatConfiguration, displayRotation, displayContent);
    }

    private final DisplayRotation mDisplayRotation;
    private final AppCompatConfiguration mAppCompatConfiguration;
    private final DisplayContent mDisplayContent;

    private DisplayRotationImmersiveAppCompatPolicy(
            @NonNull final AppCompatConfiguration appCompatConfiguration,
            @NonNull final DisplayRotation displayRotation,
            @NonNull final DisplayContent displayContent) {
        mDisplayRotation = displayRotation;
        mAppCompatConfiguration = appCompatConfiguration;
        mDisplayContent = displayContent;
    }

    /**
     * Returns {@code true} if the orientation update should be skipped and it will update when
     * transition is done. This is to keep the orientation which was preserved by
     * {@link #isRotationLockEnforced} from being changed by a transient launch (i.e. recents).
     */
    boolean deferOrientationUpdate() {
        if (mDisplayRotation.getUserRotation() != USER_ROTATION_FREE
                || mDisplayRotation.getLastOrientation() != SCREEN_ORIENTATION_UNSPECIFIED) {
            return false;
        }
        final WindowOrientationListener orientationListener =
                mDisplayRotation.getOrientationListener();
        if (orientationListener == null
                || orientationListener.getProposedRotation() == mDisplayRotation.getRotation()) {
            return false;
        }
        // The above conditions mean that isRotationLockEnforced might have taken effect:
        // Auto-rotation is enabled and the proposed rotation is not applied.
        // Then the update should defer until the transition idle to avoid disturbing animation.
        if (!mDisplayContent.mTransitionController.hasTransientLaunch(mDisplayContent)) {
            return false;
        }
        mDisplayContent.mTransitionController.mStateValidators.add(() -> {
            if (!isRotationLockEnforcedLocked(orientationListener.getProposedRotation())) {
                mDisplayContent.mWmService.updateRotation(false /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            }
        });
        return true;
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
     *   {@link AppCompatConfiguration#isDisplayRotationImmersiveAppCompatPolicyEnabled}
     * </ul>
     *
     * @param proposedRotation new proposed {@link Surface.Rotation} for the screen.
     * @return {@code true}, if there is a need to lock screen rotation, {@code false} otherwise.
     */
    boolean isRotationLockEnforced(@Surface.Rotation final int proposedRotation) {
        if (!mAppCompatConfiguration.isDisplayRotationImmersiveAppCompatPolicyEnabled()) {
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
