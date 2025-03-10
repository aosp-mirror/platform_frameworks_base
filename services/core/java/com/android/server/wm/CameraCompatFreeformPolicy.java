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
import static android.app.WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;
import static android.app.WindowConfiguration.WINDOW_CONFIG_DISPLAY_ROTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;

import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.CameraCompatTaskInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.WmProtoLogGroups;

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

    // TODO(b/380840084): Consider moving this to the CameraStateMonitor, and keeping track of
    // all current camera activities, especially when the camera access is switching from one app to
    // another.
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

    // Refreshing only when configuration changes after applying camera compat treatment.
    @Override
    public boolean shouldRefreshActivity(@NonNull ActivityRecord activity,
            @NonNull Configuration newConfig,
            @NonNull Configuration lastReportedConfig) {
        return isTreatmentEnabledForActivity(activity, /* shouldCheckOrientation= */ true)
                && haveCameraCompatAttributesChanged(newConfig, lastReportedConfig);
    }

    private boolean haveCameraCompatAttributesChanged(@NonNull Configuration newConfig,
            @NonNull Configuration lastReportedConfig) {
        // Camera compat treatment changes the following:
        // - Letterboxes app bounds to camera compat aspect ratio in app's requested orientation,
        // - Changes display rotation so it matches what the app expects in its chosen orientation,
        // - Rotate-and-crop camera feed to match that orientation (this changes iff the display
        //     rotation changes, so no need to check).
        final long diff = newConfig.windowConfiguration.diff(lastReportedConfig.windowConfiguration,
                /* compareUndefined= */ true);
        final boolean appBoundsChanged = (diff & WINDOW_CONFIG_APP_BOUNDS) != 0;
        final boolean displayRotationChanged = (diff & WINDOW_CONFIG_DISPLAY_ROTATION) != 0;
        return appBoundsChanged || displayRotationChanged;
    }

    @Override
    public void onCameraOpened(@NonNull ActivityRecord cameraActivity) {
        // Do not check orientation outside of the config recompute, as the app's orientation intent
        // might be obscured by a fullscreen override. Especially for apps which have a camera
        // functionality which is not the main focus of the app: while most of the app might work
        // well in fullscreen, often the camera setup still assumes it will run on a portrait device
        // in its natural orientation and comes out stretched or sideways.
        // Config recalculation will later check the original orientation to avoid applying
        // treatment to apps optimized for large screens.
        if (!isTreatmentEnabledForActivity(cameraActivity, /* shouldCheckOrientation= */ false)) {
            return;
        }

        mCameraTask = cameraActivity.getTask();
        updateAndDispatchCameraConfiguration();
    }

    @Override
    public boolean canCameraBeClosed(@NonNull String cameraId) {
        // Top activity in the same task as the camera activity, or `null` if the task is
        // closed.
        final ActivityRecord topActivity = getTopActivityFromCameraTask();
        if (topActivity != null) {
            if (isActivityForCameraIdRefreshing(topActivity, cameraId)) {
                ProtoLog.v(WmProtoLogGroups.WM_DEBUG_STATES,
                        "Display id=%d is notified that Camera %s is closed but activity is"
                                + " still refreshing. Rescheduling an update.",
                        mDisplayContent.mDisplayId, cameraId);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onCameraClosed() {
        // Top activity in the same task as the camera activity, or `null` if the task is
        // closed.
        final ActivityRecord topActivity = getTopActivityFromCameraTask();
        // Only clean up if the camera is not running - this close signal could be from switching
        // cameras (e.g. back to front camera, and vice versa).
        if (topActivity == null || !mCameraStateMonitor.isCameraRunningForActivity(topActivity)) {
            updateAndDispatchCameraConfiguration();
            mCameraTask = null;
        }
    }

    private void updateAndDispatchCameraConfiguration() {
        if (mCameraTask == null) {
            return;
        }
        final ActivityRecord activity = getTopActivityFromCameraTask();
        if (activity != null) {
            activity.recomputeConfiguration();
            mCameraTask.dispatchTaskInfoChangedIfNeeded(/* force= */ true);
            updateCompatibilityInfo(activity);
            activity.ensureActivityConfiguration(/* ignoreVisibility= */ true);
        } else {
            mCameraTask.dispatchTaskInfoChangedIfNeeded(/* force= */ true);
        }
    }

    private void updateCompatibilityInfo(@NonNull ActivityRecord activityRecord) {
        final CompatibilityInfo compatibilityInfo = activityRecord.mAtmService
                .compatibilityInfoForPackageLocked(activityRecord.info.applicationInfo);
        compatibilityInfo.applicationDisplayRotation =
                CameraCompatTaskInfo.getDisplayRotationFromCameraCompatMode(
                        getCameraCompatMode(activityRecord));
        try {
            // TODO(b/380840084): Consider using a ClientTransaction for this update.
            activityRecord.app.getThread().updatePackageCompatibilityInfo(
                    activityRecord.packageName, compatibilityInfo);
        } catch (RemoteException e) {
            ProtoLog.w(WmProtoLogGroups.WM_DEBUG_STATES,
                    "Unable to update CompatibilityInfo for app %s", activityRecord.app);
        }
    }

    boolean shouldCameraCompatControlOrientation(@NonNull ActivityRecord activity) {
        return isCameraRunningAndWindowingModeEligible(activity);
    }

    boolean isCameraRunningAndWindowingModeEligible(@NonNull ActivityRecord activity) {
        return  activity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldApplyFreeformTreatmentForCameraCompat()
                && activity.inFreeformWindowingMode()
                && mCameraStateMonitor.isCameraRunningForActivity(activity);
    }

    boolean shouldCameraCompatControlAspectRatio(@NonNull ActivityRecord activity) {
        // Camera compat should direct aspect ratio when in camera compat mode, unless an app has a
        // different camera compat aspect ratio set: this allows per-app camera compat override
        // aspect ratio to be smaller than the default.
        return isInFreeformCameraCompatMode(activity) && !activity.mAppCompatController
                .getAppCompatCameraOverrides().isOverrideMinAspectRatioForCameraEnabled();
    }

    boolean isInFreeformCameraCompatMode(@NonNull ActivityRecord activity) {
        return getCameraCompatMode(activity) != CAMERA_COMPAT_FREEFORM_NONE;
    }

    float getCameraCompatAspectRatio(@NonNull ActivityRecord activityRecord) {
        if (shouldCameraCompatControlAspectRatio(activityRecord)) {
            return activityRecord.mWmService.mAppCompatConfiguration.getCameraCompatAspectRatio();
        }

        return MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
    }

    @CameraCompatTaskInfo.FreeformCameraCompatMode
    int getCameraCompatMode(@NonNull ActivityRecord topActivity) {
        if (!isTreatmentEnabledForActivity(topActivity, /* shouldCheckOrientation= */ true)) {
            return CAMERA_COMPAT_FREEFORM_NONE;
        }
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
     *     <li>The app has a fixed orientation if {@code checkOrientation} is true.
     *     <li>The app is in freeform windowing mode.
     * </ul>
     *
     * @param checkOrientation Whether to take apps orientation into account for this check. Only
     *                         fixed-orientation apps should be targeted, but this might be
     *                         obscured by OEMs via fullscreen override and the app's original
     *                         intent inaccessible when the camera opens. Thus, policy would pass
     *                         {@code false} here when considering whether to trigger config
     *                         recalculation, and later pass {@code true} during recalculation.
     */
    @VisibleForTesting
    boolean isTreatmentEnabledForActivity(@NonNull ActivityRecord activity,
            boolean checkOrientation) {
        int orientation = activity.getRequestedConfigurationOrientation();
        return activity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldApplyFreeformTreatmentForCameraCompat()
                && mCameraStateMonitor.isCameraRunningForActivity(activity)
                && (!checkOrientation || orientation != ORIENTATION_UNDEFINED)
                && activity.inFreeformWindowingMode()
                // "locked" and "nosensor" values are often used by camera apps that can't
                // handle dynamic changes so we shouldn't force-letterbox them.
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_NOSENSOR
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_LOCKED
                // TODO(b/332665280): investigate whether we can support activity embedding.
                && !activity.isEmbedded();
    }

    @Nullable
    private ActivityRecord getTopActivityFromCameraTask() {
        return mCameraTask != null
                ? mCameraTask.getTopActivity(/* isFinishing */ false, /* includeOverlays */ false)
                : null;
    }

    private boolean isActivityForCameraIdRefreshing(@NonNull ActivityRecord topActivity,
            @NonNull String cameraId) {
        if (!isTreatmentEnabledForActivity(topActivity, /* checkOrientation= */ true)
                || !mCameraStateMonitor.isCameraWithIdRunningForActivity(topActivity, cameraId)) {
            return false;
        }
        return topActivity.mAppCompatController.getAppCompatCameraOverrides().isRefreshRequested();
    }
}
