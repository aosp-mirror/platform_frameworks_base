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

import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.CameraCompatTaskInfo;
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
        final boolean needsCameraCompatFreeformPolicy =
                Flags.enableCameraCompatForDesktopWindowing()
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

    static void onActivityRefreshed(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy != null && cameraPolicy.mActivityRefresher != null) {
            cameraPolicy.mActivityRefresher.onActivityRefreshed(activity);
        }
    }

    @Nullable
    static AppCompatCameraPolicy getAppCompatCameraPolicy(@NonNull ActivityRecord activityRecord) {
        return activityRecord.mDisplayContent != null
                ? activityRecord.mDisplayContent.mAppCompatCameraPolicy : null;
    }

    /**
     * "Refreshes" activity by going through "stopped -> resumed" or "paused -> resumed" cycle.
     * This allows to clear cached values in apps (e.g. display or camera rotation) that influence
     * camera preview and can lead to sideways or stretching issues persisting even after force
     * rotation.
     */
    static void onActivityConfigurationChanging(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig, @NonNull Configuration lastReportedConfig) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy != null && cameraPolicy.mActivityRefresher != null) {
            cameraPolicy.mActivityRefresher.onActivityConfigurationChanging(activity, newConfig,
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

    static boolean isActivityEligibleForOrientationOverride(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        return cameraPolicy != null && cameraPolicy.mDisplayRotationCompatPolicy != null
                && cameraPolicy.mDisplayRotationCompatPolicy
                        .isActivityEligibleForOrientationOverride(activity);
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
    static boolean isTreatmentEnabledForActivity(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        return cameraPolicy != null && cameraPolicy.mDisplayRotationCompatPolicy != null
                && cameraPolicy.mDisplayRotationCompatPolicy
                        .isTreatmentEnabledForActivity(activity);
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

    // TODO(b/369070416): have policies implement the same interface.
    static boolean shouldCameraCompatControlOrientation(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy == null) {
            return false;
        }
        return (cameraPolicy.mDisplayRotationCompatPolicy != null
                        && cameraPolicy.mDisplayRotationCompatPolicy
                                .shouldCameraCompatControlOrientation(activity))
                || (cameraPolicy.mCameraCompatFreeformPolicy != null
                        && cameraPolicy.mCameraCompatFreeformPolicy
                                .shouldCameraCompatControlOrientation(activity));
    }

    // TODO(b/369070416): have policies implement the same interface.
    static boolean shouldCameraCompatControlAspectRatio(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy == null) {
            return false;
        }
        return (cameraPolicy.mDisplayRotationCompatPolicy != null
                        && cameraPolicy.mDisplayRotationCompatPolicy
                                .shouldCameraCompatControlAspectRatio(activity))
                || (cameraPolicy.mCameraCompatFreeformPolicy != null
                        && cameraPolicy.mCameraCompatFreeformPolicy
                                .shouldCameraCompatControlAspectRatio(activity));
    }

    // TODO(b/369070416): have policies implement the same interface.
    /**
     * @return {@code true} if the Camera is active for the provided {@link ActivityRecord} and
     * any camera compat treatment could be triggered for the current windowing mode.
     */
    private static boolean isCameraRunningAndWindowingModeEligible(
            @NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy == null) {
            return false;
        }
        return (cameraPolicy.mDisplayRotationCompatPolicy != null
                && cameraPolicy.mDisplayRotationCompatPolicy
                        .isCameraRunningAndWindowingModeEligible(activity,
                                /* mustBeFullscreen */ true))
                || (cameraPolicy.mCameraCompatFreeformPolicy != null
                        && cameraPolicy.mCameraCompatFreeformPolicy
                                .isCameraRunningAndWindowingModeEligible(activity));
    }

    @Nullable
    String getSummaryForDisplayRotationHistoryRecord() {
        return mDisplayRotationCompatPolicy != null
                ? mDisplayRotationCompatPolicy.getSummaryForDisplayRotationHistoryRecord()
                : null;
    }

    // TODO(b/369070416): have policies implement the same interface.
    static float getCameraCompatMinAspectRatio(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        if (cameraPolicy == null) {
            return 1.0f;
        }
        float displayRotationCompatPolicyAspectRatio =
                cameraPolicy.mDisplayRotationCompatPolicy != null
                ? cameraPolicy.mDisplayRotationCompatPolicy.getCameraCompatAspectRatio(activity)
                : MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
        float cameraCompatFreeformPolicyAspectRatio =
                cameraPolicy.mCameraCompatFreeformPolicy != null
                ? cameraPolicy.mCameraCompatFreeformPolicy.getCameraCompatAspectRatio(activity)
                : MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
        return Math.max(displayRotationCompatPolicyAspectRatio,
                cameraCompatFreeformPolicyAspectRatio);
    }

    @CameraCompatTaskInfo.FreeformCameraCompatMode
    static int getCameraCompatFreeformMode(@NonNull ActivityRecord activity) {
        final AppCompatCameraPolicy cameraPolicy = getAppCompatCameraPolicy(activity);
        return cameraPolicy != null && cameraPolicy.mCameraCompatFreeformPolicy != null
                ? cameraPolicy.mCameraCompatFreeformPolicy.getCameraCompatMode(activity)
                : CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
    }

    /**
     * Whether we should apply the min aspect ratio per-app override only when an app is connected
     * to the camera.
     */
    static boolean shouldOverrideMinAspectRatioForCamera(@NonNull ActivityRecord activityRecord) {
        return AppCompatCameraPolicy.isCameraRunningAndWindowingModeEligible(activityRecord)
                && activityRecord.mAppCompatController.getAppCompatCameraOverrides()
                        .isOverrideMinAspectRatioForCameraEnabled();
    }
}
