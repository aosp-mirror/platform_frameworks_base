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

import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE;
import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.ProtoLogGroup;
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

    @Nullable
    private Task mCameraTask;

    /**
     * Value toggled on {@link #start()} to {@code true} and on {@link #dispose()} to {@code false}.
     */
    private boolean mIsRunning;

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
        mIsRunning = true;
    }

    /** Releases camera callback listener. */
    void dispose() {
        mCameraStateMonitor.removeCameraStateListener(this);
        mActivityRefresher.removeEvaluator(this);
        mIsRunning = false;
    }

    @VisibleForTesting
    boolean isRunning() {
        return mIsRunning;
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
    public void onCameraOpened(@NonNull ActivityRecord cameraActivity,
            @NonNull String cameraId) {
        if (!isTreatmentEnabledForActivity(cameraActivity)) {
            return;
        }
        final int existingCameraCompatMode = cameraActivity.mAppCompatController
                .getAppCompatCameraOverrides()
                        .getFreeformCameraCompatMode();
        final int newCameraCompatMode = getCameraCompatMode(cameraActivity);
        if (newCameraCompatMode != existingCameraCompatMode) {
            mIsCameraCompatTreatmentPending = true;
            mCameraTask = cameraActivity.getTask();
            cameraActivity.mAppCompatController.getAppCompatCameraOverrides()
                    .setFreeformCameraCompatMode(newCameraCompatMode);
            forceUpdateActivityAndTask(cameraActivity);
        } else {
            mIsCameraCompatTreatmentPending = false;
        }
    }

    @Override
    public boolean onCameraClosed(@NonNull String cameraId) {
        // Top activity in the same task as the camera activity, or `null` if the task is
        // closed.
        final ActivityRecord topActivity = mCameraTask != null
                ? mCameraTask.getTopActivity(/* isFinishing */ false, /* includeOverlays */ false)
                : null;
        if (topActivity != null) {
            if (isActivityForCameraIdRefreshing(topActivity, cameraId)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_STATES,
                        "Display id=%d is notified that Camera %s is closed but activity is"
                                + " still refreshing. Rescheduling an update.",
                        mDisplayContent.mDisplayId, cameraId);
                return false;
            }
        }
        mCameraTask = null;
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
        final int appOrientation = topActivity.getRequestedConfigurationOrientation();
        // It is very important to check the original (actual) display rotation, and not the
        // sandboxed rotation that camera compat treatment sets.
        final DisplayInfo displayInfo = topActivity.mWmService.mDisplayManagerInternal
                .getDisplayInfo(topActivity.getDisplayId());
        // This treatment targets only devices with portrait natural orientation, which most tablets
        // have.
        // TODO(b/365725400): handle landscape natural orientation.
        if (displayInfo.getNaturalHeight() > displayInfo.getNaturalWidth()) {
            if (appOrientation == ORIENTATION_PORTRAIT) {
                if (isDisplayRotationPortrait(displayInfo.rotation)) {
                    return CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT;
                } else {
                    return CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE;
                }
            } else if (appOrientation == ORIENTATION_LANDSCAPE) {
                if (isDisplayRotationPortrait(displayInfo.rotation)) {
                    return CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT;
                } else {
                    return CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE;
                }
            }
        }

        return CAMERA_COMPAT_FREEFORM_NONE;
    }

    private static boolean isDisplayRotationPortrait(@Surface.Rotation int displayRotation) {
        return displayRotation == ROTATION_0 || displayRotation == ROTATION_180;
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

    private boolean isActivityForCameraIdRefreshing(@NonNull ActivityRecord topActivity,
            @NonNull String cameraId) {
        if (!isTreatmentEnabledForActivity(topActivity)
                || mCameraStateMonitor.isCameraWithIdRunningForActivity(topActivity, cameraId)) {
            return false;
        }
        return topActivity.mAppCompatController.getAppCompatCameraOverrides().isRefreshRequested();
    }
}
