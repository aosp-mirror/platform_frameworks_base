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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Display.TYPE_INTERNAL;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.server.wm.DisplayRotationReversionController.REVERSION_TYPE_CAMERA_COMPAT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.RemoteException;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.UiThread;

/**
 * Controls camera compatibility treatment that handles orientation mismatch between camera
 * buffers and an app window for a particular display that can lead to camera issues like sideways
 * or stretched viewfinder.
 *
 * <p>This includes force rotation of fixed orientation activities connected to the camera.
 *
 * <p>The treatment is enabled for internal displays that have {@code ignoreOrientationRequest}
 * display setting enabled and when {@code
 * R.bool.config_isWindowManagerCameraCompatTreatmentEnabled} is {@code true}.
 */
 // TODO(b/261444714): Consider moving Camera-specific logic outside of the WM Core path
class DisplayRotationCompatPolicy implements CameraStateMonitor.CameraCompatStateListener {

    // Delay for updating display rotation after Camera connection is closed. Needed to avoid
    // rotation flickering when an app is flipping between front and rear cameras or when size
    // compat mode is restarted.
    // TODO(b/263114289): Consider associating this delay with a specific activity so that if
    // the new non-camera activity started on top of the camer one we can rotate faster.
    private static final int CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS = 2000;
    // Delay for updating display rotation after Camera connection is opened. This delay is
    // selected to be long enough to avoid conflicts with transitions on the app's side.
    // Using a delay < CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS to avoid flickering when an app
    // is flipping between front and rear cameras (in case requested orientation changes at
    // runtime at the same time) or when size compat mode is restarted.
    private static final int CAMERA_OPENED_ROTATION_UPDATE_DELAY_MS =
            CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS / 2;
    // Delay for ensuring that onActivityRefreshed is always called after an activity refresh. The
    // client process may not always report the event back to the server, such as process is
    // crashed or got killed.
    private static final int REFRESH_CALLBACK_TIMEOUT_MS = 2000;

    private final DisplayContent mDisplayContent;
    private final WindowManagerService mWmService;
    private final CameraStateMonitor mCameraStateMonitor;
    private final Handler mHandler;

    @ScreenOrientation
    private int mLastReportedOrientation = SCREEN_ORIENTATION_UNSET;

    DisplayRotationCompatPolicy(@NonNull DisplayContent displayContent, Handler handler,
            @NonNull CameraStateMonitor cameraStateMonitor) {
        // This constructor is called from DisplayContent constructor. Don't use any fields in
        // DisplayContent here since they aren't guaranteed to be set.
        mHandler = handler;
        mDisplayContent = displayContent;
        mWmService = displayContent.mWmService;
        mCameraStateMonitor = cameraStateMonitor;
        mCameraStateMonitor.addCameraStateListener(this);
    }

    /** Releases camera state listener. */
    void dispose() {
        mCameraStateMonitor.removeCameraStateListener(this);
    }

    /**
     * Determines orientation for Camera compatibility.
     *
     * <p>The goal of this function is to compute a orientation which would align orientations of
     * portrait app window and natural orientation of the device and set opposite to natural
     * orientation for a landscape app window. This is one of the strongest assumptions that apps
     * make when they implement camera previews. Since app and natural display orientations aren't
     * guaranteed to match, the rotation can cause letterboxing.
     *
     * <p>If treatment isn't applicable returns {@link SCREEN_ORIENTATION_UNSPECIFIED}. See {@link
     * #shouldComputeCameraCompatOrientation} for conditions enabling the treatment.
     */
    @ScreenOrientation
    int getOrientation() {
        mLastReportedOrientation = getOrientationInternal();
        if (mLastReportedOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            rememberOverriddenOrientationIfNeeded();
        } else {
            restoreOverriddenOrientationIfNeeded();
        }
        return mLastReportedOrientation;
    }

    @ScreenOrientation
    private synchronized int getOrientationInternal() {
        if (!isTreatmentEnabledForDisplay()) {
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }
        final ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                /* considerKeyguardState= */ true);
        if (!isTreatmentEnabledForActivity(topActivity)) {
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }
        boolean isPortraitActivity =
                topActivity.getRequestedConfigurationOrientation() == ORIENTATION_PORTRAIT;
        boolean isNaturalDisplayOrientationPortrait =
                mDisplayContent.getNaturalOrientation() == ORIENTATION_PORTRAIT;
        // Rotate portrait-only activity in the natural orientation of the displays (and in the
        // opposite to natural orientation for landscape-only) since many apps assume that those
        // are aligned when they compute orientation of the preview.
        // This means that even for a landscape-only activity and a device with landscape natural
        // orientation this would return SCREEN_ORIENTATION_PORTRAIT because an assumption that
        // natural orientation = portrait window = portait camera is the main wrong assumption
        // that apps make when they implement camera previews so landscape windows need be
        // rotated in the orientation oposite to the natural one even if it's portrait.
        // TODO(b/261475895): Consider allowing more rotations for "sensor" and "user" versions
        // of the portrait and landscape orientation requests.
        final int orientation = (isPortraitActivity && isNaturalDisplayOrientationPortrait)
                || (!isPortraitActivity && !isNaturalDisplayOrientationPortrait)
                        ? SCREEN_ORIENTATION_PORTRAIT
                        : SCREEN_ORIENTATION_LANDSCAPE;
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d is ignoring all orientation requests, camera is active "
                        + "and the top activity is eligible for force rotation, return %s,"
                        + "portrait activity: %b, is natural orientation portrait: %b.",
                mDisplayContent.mDisplayId, screenOrientationToString(orientation),
                isPortraitActivity, isNaturalDisplayOrientationPortrait);
        return orientation;
    }

    /**
     * "Refreshes" activity by going through "stopped -> resumed" or "paused -> resumed" cycle.
     * This allows to clear cached values in apps (e.g. display or camera rotation) that influence
     * camera preview and can lead to sideways or stretching issues persisting even after force
     * rotation.
     */
    void onActivityConfigurationChanging(ActivityRecord activity, Configuration newConfig,
            Configuration lastReportedConfig) {
        if (!isTreatmentEnabledForDisplay()
                || !mWmService.mLetterboxConfiguration.isCameraCompatRefreshEnabled()
                || !shouldRefreshActivity(activity, newConfig, lastReportedConfig)) {
            return;
        }
        boolean cycleThroughStop =
                mWmService.mLetterboxConfiguration
                        .isCameraCompatRefreshCycleThroughStopEnabled()
                && !activity.mLetterboxUiController
                        .shouldRefreshActivityViaPauseForCameraCompat();
        try {
            activity.mLetterboxUiController.setIsRefreshAfterRotationRequested(true);
            ProtoLog.v(WM_DEBUG_STATES,
                    "Refreshing activity for camera compatibility treatment, "
                            + "activityRecord=%s", activity);
            final RefreshCallbackItem refreshCallbackItem = RefreshCallbackItem.obtain(
                    activity.token, cycleThroughStop ? ON_STOP : ON_PAUSE);
            final ResumeActivityItem resumeActivityItem = ResumeActivityItem.obtain(
                    activity.token, /* isForward */ false, /* shouldSendCompatFakeFocus */ false);
            activity.mAtmService.getLifecycleManager().scheduleTransactionAndLifecycleItems(
                    activity.app.getThread(), refreshCallbackItem, resumeActivityItem);
            mHandler.postDelayed(
                    () -> onActivityRefreshed(activity),
                    REFRESH_CALLBACK_TIMEOUT_MS);
        } catch (RemoteException e) {
            activity.mLetterboxUiController.setIsRefreshAfterRotationRequested(false);
        }
    }

    void onActivityRefreshed(@NonNull ActivityRecord activity) {
        activity.mLetterboxUiController.setIsRefreshAfterRotationRequested(false);
    }

    /**
     * Notifies that animation in {@link ScreenRotationAnimation} has finished.
     *
     * <p>This class uses this signal as a trigger for notifying the user about forced rotation
     * reason with the {@link Toast}.
     */
    void onScreenRotationAnimationFinished() {
        final ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                /* considerKeyguardState= */ true);
        if (!isTreatmentEnabledForDisplay()
                || !isTreatmentEnabledForActivity(topActivity)) {
            return;
        }
        showToast(R.string.display_rotation_camera_compat_toast_after_rotation);
    }

    String getSummaryForDisplayRotationHistoryRecord() {
        String summaryIfEnabled = "";
        if (isTreatmentEnabledForDisplay()) {
            ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                    /* considerKeyguardState= */ true);
            summaryIfEnabled =
                    " mLastReportedOrientation="
                            + screenOrientationToString(mLastReportedOrientation)
                    + " topActivity="
                            + (topActivity == null ? "null" : topActivity.shortComponentName)
                    + " isTreatmentEnabledForActivity="
                            + isTreatmentEnabledForActivity(topActivity)
                            + "mCameraStateMonitor="
                            + mCameraStateMonitor.getSummary();
        }
        return "DisplayRotationCompatPolicy{"
                + " isTreatmentEnabledForDisplay=" + isTreatmentEnabledForDisplay()
                + summaryIfEnabled
                + " }";
    }

    private void restoreOverriddenOrientationIfNeeded() {
        if (!isOrientationOverridden()) {
            return;
        }
        if (mDisplayContent.getRotationReversionController().revertOverride(
                REVERSION_TYPE_CAMERA_COMPAT)) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "Reverting orientation after camera compat force rotation");
            // Reset last orientation source since we have reverted the orientation.
            mDisplayContent.mLastOrientationSource = null;
        }
    }

    private boolean isOrientationOverridden() {
        return mDisplayContent.getRotationReversionController().isOverrideActive(
                REVERSION_TYPE_CAMERA_COMPAT);
    }

    private void rememberOverriddenOrientationIfNeeded() {
        if (!isOrientationOverridden()) {
            mDisplayContent.getRotationReversionController().beforeOverrideApplied(
                    REVERSION_TYPE_CAMERA_COMPAT);
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "Saving original orientation before camera compat, last orientation is %d",
                    mDisplayContent.getLastOrientation());
        }
    }

    // Refreshing only when configuration changes after rotation or camera split screen aspect ratio
    // treatment is enabled
    private boolean shouldRefreshActivity(ActivityRecord activity, Configuration newConfig,
            Configuration lastReportedConfig) {
        final boolean displayRotationChanged = (newConfig.windowConfiguration.getDisplayRotation()
                != lastReportedConfig.windowConfiguration.getDisplayRotation());
        return (displayRotationChanged
                || activity.mLetterboxUiController.isCameraCompatSplitScreenAspectRatioAllowed())
                && isTreatmentEnabledForActivity(activity)
                && activity.mLetterboxUiController.shouldRefreshActivityForCameraCompat();
    }

    /**
     * Whether camera compat treatment is enabled for the display.
     *
     * <p>Conditions that need to be met:
     * <ul>
     *     <li>{@code R.bool.config_isWindowManagerCameraCompatTreatmentEnabled} is {@code true}.
     *     <li>Setting {@code ignoreOrientationRequest} is enabled for the display.
     *     <li>Associated {@link DisplayContent} is for internal display. See b/225928882
     *     that tracks supporting external displays in the future.
     * </ul>
     */
    private boolean isTreatmentEnabledForDisplay() {
        return mWmService.mLetterboxConfiguration.isCameraCompatTreatmentEnabled()
                && mDisplayContent.getIgnoreOrientationRequest()
                // TODO(b/225928882): Support camera compat rotation for external displays
                && mDisplayContent.getDisplay().getType() == TYPE_INTERNAL;
    }

    boolean isActivityEligibleForOrientationOverride(@NonNull ActivityRecord activity) {
        return isTreatmentEnabledForDisplay()
                && isCameraActive(activity, /* mustBeFullscreen */ true)
                && activity.mLetterboxUiController.shouldForceRotateForCameraCompat();
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
        return isTreatmentEnabledForActivity(activity, /* mustBeFullscreen */ true);
    }

    boolean isCameraActive(@NonNull ActivityRecord activity, boolean mustBeFullscreen) {
        // Checking windowing mode on activity level because we don't want to
        // apply treatment in case of activity embedding.
        return (!mustBeFullscreen || !activity.inMultiWindowMode())
                && mCameraStateMonitor.isCameraRunningForActivity(activity);
    }

    private boolean isTreatmentEnabledForActivity(@Nullable ActivityRecord activity,
            boolean mustBeFullscreen) {
        return activity != null && isCameraActive(activity, mustBeFullscreen)
                && activity.getRequestedConfigurationOrientation() != ORIENTATION_UNDEFINED
                // "locked" and "nosensor" values are often used by camera apps that can't
                // handle dynamic changes so we shouldn't force rotate them.
                && activity.getOverrideOrientation() != SCREEN_ORIENTATION_NOSENSOR
                && activity.getOverrideOrientation() != SCREEN_ORIENTATION_LOCKED
                && activity.mLetterboxUiController.shouldForceRotateForCameraCompat();
    }

    @Override
    public boolean onCameraOpened(@NonNull ActivityRecord cameraActivity,
            @NonNull String cameraId) {
        // Checking whether an activity in fullscreen rather than the task as this camera
        // compat treatment doesn't cover activity embedding.
        if (cameraActivity.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            cameraActivity.mLetterboxUiController.recomputeConfigurationForCameraCompatIfNeeded();
            mDisplayContent.updateOrientation();
            return true;
        }
        // Checking that the whole app is in multi-window mode as we shouldn't show toast
        // for the activity embedding case.
        if (cameraActivity.getTask().getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW
                && isTreatmentEnabledForActivity(
                cameraActivity, /* mustBeFullscreen */ false)) {
            final PackageManager packageManager = mWmService.mContext.getPackageManager();
            try {
                showToast(
                        R.string.display_rotation_camera_compat_toast_in_multi_window,
                        (String) packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(cameraActivity.packageName,
                                        /* flags */ 0)));
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                ProtoLog.e(WM_DEBUG_ORIENTATION,
                        "DisplayRotationCompatPolicy: Multi-window toast not shown as "
                                + "package '%s' cannot be found.",
                        cameraActivity.packageName);
            }
        }
        return false;
    }

    @VisibleForTesting
    void showToast(@StringRes int stringRes) {
        UiThread.getHandler().post(
                () -> Toast.makeText(mWmService.mContext, stringRes, Toast.LENGTH_LONG).show());
    }

    @VisibleForTesting
    void showToast(@StringRes int stringRes, @NonNull String applicationLabel) {
        UiThread.getHandler().post(
                () -> Toast.makeText(
                        mWmService.mContext,
                        mWmService.mContext.getString(stringRes, applicationLabel),
                        Toast.LENGTH_LONG).show());
    }

    @Override
    public boolean onCameraClosed(@NonNull ActivityRecord cameraActivity,
            @NonNull String cameraId) {
        synchronized (this) {
            // TODO(b/336474959): Once refresh is implemented in `CameraCompatFreeformPolicy`,
            // consider checking this in CameraStateMonitor before notifying the listeners (this).
            if (isActivityForCameraIdRefreshing(cameraId)) {
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Display id=%d is notified that camera is closed but activity is"
                                + " still refreshing. Rescheduling an update.",
                        mDisplayContent.mDisplayId);
                return false;
            }
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d is notified that Camera is closed, updating rotation.",
                mDisplayContent.mDisplayId);
        final ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                /* considerKeyguardState= */ true);
        if (topActivity == null
                // Checking whether an activity in fullscreen rather than the task as this
                // camera compat treatment doesn't cover activity embedding.
                || topActivity.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        topActivity.mLetterboxUiController.recomputeConfigurationForCameraCompatIfNeeded();
        mDisplayContent.updateOrientation();
        return true;
    }

    // TODO(b/336474959): Do we need cameraId here?
    private boolean isActivityForCameraIdRefreshing(String cameraId) {
        final ActivityRecord topActivity = mDisplayContent.topRunningActivity(
                /* considerKeyguardState= */ true);
        if (!isTreatmentEnabledForActivity(topActivity)
                || !mCameraStateMonitor.isCameraWithIdRunningForActivity(topActivity, cameraId)) {
            return false;
        }
        return topActivity.mLetterboxUiController.isRefreshAfterRotationRequested();
    }
}
