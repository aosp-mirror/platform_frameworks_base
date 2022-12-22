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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Display.TYPE_INTERNAL;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.util.Map;
import java.util.Set;

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
final class DisplayRotationCompatPolicy {

    // Delay for updating display rotation after Camera connection is closed. Needed to avoid
    // rotation flickering when an app is flipping between front and rear cameras or when size
    // compat mode is restarted.
    // TODO(b/263114289): Consider associating this delay with a specific activity so that if
    // the new non-camera activity started on top of the camer one we can rotate faster.
    private static final int CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS = 2000;
    // Delay for updating display rotation after Camera connection is opened. This delay is
    // selected to be long enough to avoid conflicts with transitions on the app's side.
    // Using half CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS to avoid flickering when an app
    // is flipping between front and rear cameras (in case requested orientation changes at
    // runtime at the same time) or when size compat mode is restarted.
    private static final int CAMERA_OPENED_ROTATION_UPDATE_DELAY_MS =
            CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS / 2;

    private final DisplayContent mDisplayContent;
    private final WindowManagerService mWmService;
    private final CameraManager mCameraManager;
    private final Handler mHandler;
    // TODO(b/218352945): Add an ADB command.
    private final boolean mIsTreatmentEnabled;

    // Bi-directional map between package names and active camera IDs since we need to 1) get a
    // camera id by a package name when determining rotation; 2) get a package name by a camera id
    // when camera connection is closed and we need to clean up our records.
    @GuardedBy("this")
    private final CameraIdPackageNameBiMap mCameraIdPackageBiMap = new CameraIdPackageNameBiMap();
    @GuardedBy("this")
    private final Set<String> mScheduledToBeRemovedCameraIdSet = new ArraySet<>();
    @GuardedBy("this")
    private final Set<String> mScheduledOrientationUpdateCameraIdSet = new ArraySet<>();

    private final CameraManager.AvailabilityCallback mAvailabilityCallback =
            new  CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraOpened(@NonNull String cameraId, @NonNull String packageId) {
                    notifyCameraOpened(cameraId, packageId);
                }

                @Override
                public void onCameraClosed(@NonNull String cameraId) {
                    notifyCameraClosed(cameraId);
                }
            };

    DisplayRotationCompatPolicy(@NonNull DisplayContent displayContent) {
        this(displayContent, displayContent.mWmService.mH);
    }

    @VisibleForTesting
    DisplayRotationCompatPolicy(@NonNull DisplayContent displayContent, Handler handler) {
        // This constructor is called from DisplayContent constructor. Don't use any fields in
        // DisplayContent here since they aren't guaranteed to be set.
        mHandler = handler;
        mDisplayContent = displayContent;
        mWmService = displayContent.mWmService;
        mIsTreatmentEnabled = isTreatmentEnabled(mWmService.mContext);
        mCameraManager = mWmService.mContext.getSystemService(CameraManager.class);
        mCameraManager.registerAvailabilityCallback(
                mWmService.mContext.getMainExecutor(), mAvailabilityCallback);
    }

    static boolean isTreatmentEnabled(@NonNull Context context) {
        return context.getResources().getBoolean(
                R.bool.config_isWindowManagerCameraCompatTreatmentEnabled);
    }

    void dispose() {
        mCameraManager.unregisterAvailabilityCallback(mAvailabilityCallback);
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
    synchronized int getOrientation() {
        if (!isTreatmentEnabledForDisplay()) {
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }
        ActivityRecord topActivity = mDisplayContent.topRunningActivity(
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
        int orientation = (isPortraitActivity && isNaturalDisplayOrientationPortrait)
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
        return mIsTreatmentEnabled && mDisplayContent.getIgnoreOrientationRequest()
                // TODO(b/225928882): Support camera compat rotation for external displays
                && mDisplayContent.getDisplay().getType() == TYPE_INTERNAL;
    }

    /**
     * Whether camera compat treatment is applicable for the given activity.
     *
     * <p>Conditions that need to be met:
     * <ul>
     *     <li>{@link #isCameraActiveForPackage} is {@code true} for the activity.
     *     <li>The activity is in fullscreen
     *     <li>The activity has fixed orientation but not "locked" or "nosensor" one.
     * </ul>
     */
    private boolean isTreatmentEnabledForActivity(@Nullable ActivityRecord activity) {
        return activity != null && !activity.inMultiWindowMode()
                && activity.getRequestedConfigurationOrientation() != ORIENTATION_UNDEFINED
                // "locked" and "nosensor" values are often used by camera apps that can't
                // handle dynamic changes so we shouldn't force rotate them.
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_NOSENSOR
                && activity.getRequestedOrientation() != SCREEN_ORIENTATION_LOCKED
                && mCameraIdPackageBiMap.containsPackageName(activity.packageName);
    }

    private synchronized void notifyCameraOpened(
            @NonNull String cameraId, @NonNull String packageName) {
        // If an activity is restarting or camera is flipping, the camera connection can be
        // quickly closed and reopened.
        mScheduledToBeRemovedCameraIdSet.remove(cameraId);
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d is notified that Camera %s is open for package %s",
                mDisplayContent.mDisplayId, cameraId, packageName);
        // Some apps can’t handle configuration changes coming at the same time with Camera setup
        // so delaying orientation update to accomadate for that.
        mScheduledOrientationUpdateCameraIdSet.add(cameraId);
        mHandler.postDelayed(
                () ->  delayedUpdateOrientationWithWmLock(cameraId, packageName),
                CAMERA_OPENED_ROTATION_UPDATE_DELAY_MS);
        // TODO(b/218352945): Restart activity after forced rotation to avoid issues cased by
        // in-app caching of pre-rotation display / camera properties.
    }

    private void updateOrientationWithWmLock() {
        synchronized (mWmService.mGlobalLock) {
            mDisplayContent.updateOrientation();
        }
    }

    private void delayedUpdateOrientationWithWmLock(
            @NonNull String cameraId, @NonNull String packageName) {
        synchronized (this) {
            if (!mScheduledOrientationUpdateCameraIdSet.remove(cameraId)) {
                // Orientation update has happened already or was cancelled because
                // camera was closed.
                return;
            }
            mCameraIdPackageBiMap.put(packageName, cameraId);
        }
        updateOrientationWithWmLock();
    }

    private synchronized void notifyCameraClosed(@NonNull String cameraId) {
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d is notified that Camera %s is closed, scheduling rotation update.",
                mDisplayContent.mDisplayId, cameraId);
        mScheduledToBeRemovedCameraIdSet.add(cameraId);
        // No need to update orientation for this camera if it's already closed.
        mScheduledOrientationUpdateCameraIdSet.remove(cameraId);
        // Delay is needed to avoid rotation flickering when an app is flipping between front and
        // rear cameras or when size compat mode is restarted.
        mHandler.postDelayed(
                () -> removeCameraId(cameraId),
                CAMERA_CLOSED_ROTATION_UPDATE_DELAY_MS);
    }

    private void removeCameraId(String cameraId) {
        synchronized (this) {
            if (!mScheduledToBeRemovedCameraIdSet.remove(cameraId)) {
                // Already reconnected to this camera, no need to clean up.
                return;
            }
            mCameraIdPackageBiMap.removeCameraId(cameraId);
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d is notified that Camera %s is closed, updating rotation.",
                mDisplayContent.mDisplayId, cameraId);
        updateOrientationWithWmLock();
    }

    private static class CameraIdPackageNameBiMap {

        private final Map<String, String> mPackageToCameraIdMap = new ArrayMap<>();
        private final Map<String, String> mCameraIdToPackageMap = new ArrayMap<>();

        void put(String packageName, String cameraId) {
            // Always using the last connected camera ID for the package even for the concurrent
            // camera use case since we can't guess which camera is more important anyway.
            removePackageName(packageName);
            removeCameraId(cameraId);
            mPackageToCameraIdMap.put(packageName, cameraId);
            mCameraIdToPackageMap.put(cameraId, packageName);
        }

        boolean containsPackageName(String packageName) {
            return mPackageToCameraIdMap.containsKey(packageName);
        }

        void removeCameraId(String cameraId) {
            String packageName = mCameraIdToPackageMap.get(cameraId);
            if (packageName == null) {
                return;
            }
            mPackageToCameraIdMap.remove(packageName, cameraId);
            mCameraIdToPackageMap.remove(cameraId, packageName);
        }

        private void removePackageName(String packageName) {
            String cameraId = mPackageToCameraIdMap.get(packageName);
            if (cameraId == null) {
                return;
            }
            mPackageToCameraIdMap.remove(packageName, cameraId);
            mCameraIdToPackageMap.remove(cameraId, packageName);
        }
    }
}
