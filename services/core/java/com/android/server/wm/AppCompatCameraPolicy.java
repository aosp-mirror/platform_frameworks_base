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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.Configuration;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

/**
 * Encapsulate policy logic related to app compat display rotation.
 */
class AppCompatCameraPolicy {

    @Nullable
    @VisibleForTesting
    final CameraStateMonitor mCameraStateMonitor;
    @Nullable
    private final ActivityRefresher mActivityRefresher;
    @Nullable
    final DisplayRotationCompatPolicy mDisplayRotationCompatPolicy;
    @Nullable
    final CameraCompatFreeformPolicy mCameraCompatFreeformPolicy;

    AppCompatCameraPolicy(@NonNull WindowManagerService wmService,
            @NonNull DisplayContent displayContent) {
        // Not checking DeviceConfig value here to allow enabling via DeviceConfig
        // without the need to restart the device.
        final boolean needsDisplayRotationCompatPolicy =
                wmService.mAppCompatConfiguration.isCameraCompatTreatmentEnabledAtBuildTime();
        final boolean needsCameraCompatFreeformPolicy = Flags.cameraCompatForFreeform()
                && DesktopModeHelper.canEnterDesktopMode(wmService.mContext);
        if (needsDisplayRotationCompatPolicy || needsCameraCompatFreeformPolicy) {
            mCameraStateMonitor = new CameraStateMonitor(displayContent, wmService.mH);
            mActivityRefresher = new ActivityRefresher(wmService, wmService.mH);
            mDisplayRotationCompatPolicy =
                    needsDisplayRotationCompatPolicy ? new DisplayRotationCompatPolicy(
                            displayContent, mCameraStateMonitor, mActivityRefresher) : null;
            mCameraCompatFreeformPolicy =
                    needsCameraCompatFreeformPolicy ? new CameraCompatFreeformPolicy(displayContent,
                            mCameraStateMonitor, mActivityRefresher) : null;
        } else {
            mDisplayRotationCompatPolicy = null;
            mCameraCompatFreeformPolicy = null;
            mCameraStateMonitor = null;
            mActivityRefresher = null;
        }
    }

    void onActivityRefreshed(@NonNull ActivityRecord activity) {
        if (mActivityRefresher != null) {
            mActivityRefresher.onActivityRefreshed(activity);
        }
    }

    /**
     * "Refreshes" activity by going through "stopped -> resumed" or "paused -> resumed" cycle.
     * This allows to clear cached values in apps (e.g. display or camera rotation) that influence
     * camera preview and can lead to sideways or stretching issues persisting even after force
     * rotation.
     */
    void onActivityConfigurationChanging(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig, @NonNull Configuration lastReportedConfig) {
        if (mActivityRefresher != null) {
            mActivityRefresher.onActivityConfigurationChanging(activity, newConfig,
                    lastReportedConfig);
        }
    }

    /**
     * Notifies that animation in {@link ScreenRotationAnimation} has finished.
     *
     * <p>This class uses this signal as a trigger for notifying the user about forced rotation
     * reason with the {@link Toast}.
     */
    void onScreenRotationAnimationFinished() {
        if (mDisplayRotationCompatPolicy != null) {
            mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();
        }
    }

    boolean isActivityEligibleForOrientationOverride(@NonNull ActivityRecord activity) {
        if (mDisplayRotationCompatPolicy != null) {
            return mDisplayRotationCompatPolicy.isActivityEligibleForOrientationOverride(activity);
        }
        return false;
    }

    /**
     * Whether camera compat treatment is applicable for the given activity.
     *
     * <p>Conditions that need to be met:
     * <ul>
     *     <li>Camera is active for the package.
     *     <li>The activity is in fullscreen
     *     <li>The activity has fixed orientation but not "locked" or "nosensor" one.
     * </ul>
     */
    boolean isTreatmentEnabledForActivity(@Nullable ActivityRecord activity) {
        if (mDisplayRotationCompatPolicy != null) {
            return mDisplayRotationCompatPolicy.isTreatmentEnabledForActivity(activity);
        }
        return false;
    }

    void start() {
        if (mDisplayRotationCompatPolicy != null) {
            mDisplayRotationCompatPolicy.start();
        }
        if (mCameraCompatFreeformPolicy != null) {
            mCameraCompatFreeformPolicy.start();
        }
        if (mCameraStateMonitor != null) {
            mCameraStateMonitor.startListeningToCameraState();
        }
    }

    void dispose() {
        if (mDisplayRotationCompatPolicy != null) {
            mDisplayRotationCompatPolicy.dispose();
        }
        if (mCameraCompatFreeformPolicy != null) {
            mCameraCompatFreeformPolicy.dispose();
        }
        if (mCameraStateMonitor != null) {
            mCameraStateMonitor.dispose();
        }
    }

    boolean hasDisplayRotationCompatPolicy() {
        return mDisplayRotationCompatPolicy != null;
    }

    boolean hasCameraCompatFreeformPolicy() {
        return mCameraCompatFreeformPolicy != null;
    }

    boolean hasCameraStateMonitor() {
        return mCameraStateMonitor != null;
    }

    @ScreenOrientation
    int getOrientation() {
        return mDisplayRotationCompatPolicy != null
                ? mDisplayRotationCompatPolicy.getOrientation()
                : SCREEN_ORIENTATION_UNSPECIFIED;
    }

    /**
     * @return {@code true} if the Camera is active for the provided {@link ActivityRecord}.
     */
    boolean isCameraActive(@NonNull ActivityRecord activity, boolean mustBeFullscreen) {
        return mDisplayRotationCompatPolicy != null
                && mDisplayRotationCompatPolicy.isCameraActive(activity, mustBeFullscreen);
    }

    @Nullable
    String getSummaryForDisplayRotationHistoryRecord() {
        if (mDisplayRotationCompatPolicy != null) {
            return mDisplayRotationCompatPolicy.getSummaryForDisplayRotationHistoryRecord();
        }
        return null;
    }

    /**
     * Whether we should apply the min aspect ratio per-app override only when an app is connected
     * to the camera.
     */
    boolean shouldOverrideMinAspectRatioForCamera(@NonNull ActivityRecord activityRecord) {
        return isCameraActive(activityRecord, /* mustBeFullscreen= */ true)
                && activityRecord.mAppCompatController.getAppCompatCameraOverrides()
                        .isOverrideMinAspectRatioForCameraEnabled();
    }
}
