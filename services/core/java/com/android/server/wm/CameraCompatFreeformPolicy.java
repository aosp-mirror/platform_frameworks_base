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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.app.CameraCompatTaskInfo;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.window.flags.Flags;

/**
 * Policy for camera compatibility freeform treatment.
 *
 * <p>The treatment is applied to a fixed-orientation camera activity in freeform windowing mode.
 * The treatment letterboxes or pillarboxes the activity to the expected orientation and provides
 * changes to the camera and display orientation signals to match those expected on a portrait
 * device in that orientation (for example, on a standard phone).
 */
final class CameraCompatFreeformPolicy implements CameraStateMonitor.CameraCompatStateListener,
        ActivityRefresher.Evaluator {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "CameraCompatFreeformPolicy" : TAG_WM;

    @NonNull
    private final DisplayContent mDisplayContent;
    @NonNull
    private final ActivityRefresher mActivityRefresher;
    @NonNull
    private final CameraStateMonitor mCameraStateMonitor;

    private boolean mIsCameraCompatTreatmentPending = false;

    CameraCompatFreeformPolicy(@NonNull DisplayContent displayContent,
            @NonNull CameraStateMonitor cameraStateMonitor,
            @NonNull ActivityRefresher activityRefresher) {
        mDisplayContent = displayContent;
        mCameraStateMonitor = cameraStateMonitor;
        mActivityRefresher = activityRefresher;
    }

    void start() {
        mCameraStateMonitor.addCameraStateListener(this);
        mActivityRefresher.addEvaluator(this);
    }

    /** Releases camera callback listener. */
    void dispose() {
        mCameraStateMonitor.removeCameraStateListener(this);
        mActivityRefresher.removeEvaluator(this);
    }

    // Refreshing only when configuration changes after rotation or camera split screen aspect ratio
    // treatment is enabled.
    @Override
    public boolean shouldRefreshActivity(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig,
            @NonNull Configuration lastReportedConfig) {
        return isTreatmentEnabledForActivity(activity) && mIsCameraCompatTreatmentPending;
    }

    /**
     * Whether activity is eligible for camera compatibility free-form treatment.
     *
     * <p>The treatment is applied to a fixed-orientation camera activity in free-form windowing
     * mode. The treatment letterboxes or pillarboxes the activity to the expected orientation and
     * provides changes to the camera and display orientation signals to match those expected on a
     * portrait device in that orientation (for example, on a standard phone).
     *
     * <p>The treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Property gating the camera compatibility free-form treatment is enabled.
     *     <li>Activity isn't opted out by the device manufacturer with override.
     * </ul>
     */
    @VisibleForTesting
    boolean shouldApplyFreeformTreatmentForCameraCompat(@NonNull ActivityRecord activity) {
        return Flags.cameraCompatForFreeform() && !activity.info.isChangeEnabled(
                ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT);
    }

    @Override
    public boolean onCameraOpened(@NonNull ActivityRecord cameraActivity,
            @NonNull String cameraId) {
        if (!isTreatmentEnabledForActivity(cameraActivity)) {
            return false;
        }
        final int existingCameraCompatMode =
                cameraActivity.mLetterboxUiController.getFreeformCameraCompatMode();
        final int newCameraCompatMode = getCameraCompatMode(cameraActivity);
        if (newCameraCompatMode != existingCameraCompatMode) {
            mIsCameraCompatTreatmentPending = true;
            cameraActivity.mLetterboxUiController.setFreeformCameraCompatMode(newCameraCompatMode);
            forceUpdateActivityAndTask(cameraActivity);
            return true;
        } else {
            mIsCameraCompatTreatmentPending = false;
        }
        return false;
    }

    @Override
    public boolean onCameraClosed(@NonNull ActivityRecord cameraActivity,
            @NonNull String cameraId) {
        if (isActivityForCameraIdRefreshing(cameraId)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_STATES,
                    "Display id=%d is notified that Camera %s is closed but activity is"
                            + " still refreshing. Rescheduling an update.",
                    mDisplayContent.mDisplayId, cameraId);
            return false;
        }
        cameraActivity.mLetterboxUiController.setFreeformCameraCompatMode(
                CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE);
        forceUpdateActivityAndTask(cameraActivity);
        mIsCameraCompatTreatmentPending = false;
        return true;
    }

    private void forceUpdateActivityAndTask(ActivityRecord cameraActivity) {
        cameraActivity.recomputeConfiguration();
        cameraActivity.updateReportedConfigurationAndSend();
        Task cameraTask = cameraActivity.getTask();
        if (cameraTask != null) {
            cameraTask.dispatchTaskInfoChangedIfNeeded(/* force= */ true);
        }
    }

    private static int getCameraCompatMode(@NonNull ActivityRecord topActivity) {
        return switch (topActivity.getRequestedConfigurationOrientation()) {
            case ORIENTATION_PORTRAIT -> CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT;
            case ORIENTATION_LANDSCAPE -> CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_LANDSCAPE;
            default -> CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
        };
    }

    /**
     * Whether camera compat treatment is applicable for the given activity, ignoring its windowing
     * mode.
     *
     * <p>Conditions that need to be met:
     * <ul>
     *     <li>Treatment is enabled.
     *     <li>Camera is active for the package.
     *     <li>The app has a fixed orientation.
     *     <li>The app is in freeform windowing mode.
     * </ul>
     */
    private boolean isTreatmentEnabledForActivity(@NonNull ActivityRecord activity) {
        int orientation = activity.getRequestedConfigurationOrientation();
        return shouldApplyFreeformTreatmentForCameraCompat(activity)
                && mCameraStateMonitor.isCameraRunningForActivity(activity)
                && orientation != ORIENTATION_UNDEFINED
                && activity.inFreeformWindowingMode()
                // "locked" and "nosensor" values are often used by camera apps that can't
                // handle dynamic changes so we shouldn't force-letterbox them.
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_NOSENSOR
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_LOCKED
                // TODO(b/332665280): investigate whether we can support activity embedding.
                && !activity.isEmbedded();
    }

    private boolean isActivityForCameraIdRefreshing(@NonNull String cameraId) {
        final ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                /* considerKeyguardState= */ true);
        if (topActivity == null || !isTreatmentEnabledForActivity(topActivity)
                || mCameraStateMonitor.isCameraWithIdRunningForActivity(topActivity, cameraId)) {
            return false;
        }
        return topActivity.mLetterboxUiController.isRefreshRequested();
    }
}
