/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles launching the secure camera properly even when other applications may be using the camera
 * hardware.
 *
 * When other applications (e.g., Face Unlock) are using the camera, they must close the camera to
 * allow the secure camera to open it.  Since we want to minimize the delay when opening the secure
 * camera, other apps should close the camera at the first possible opportunity (i.e., as soon as
 * the user begins swiping to go to the secure camera).
 *
 * If the camera is unavailable when the user begins to swipe, the SecureCameraLaunchManager sends a
 * broadcast to tell other apps to close the camera.  When and if the user completes their swipe to
 * launch the secure camera, the SecureCameraLaunchManager delays launching the secure camera until
 * a callback indicates that the camera has become available.  If it doesn't receive that callback
 * within a specified timeout period, the secure camera is launched anyway.
 *
 * Ideally, the secure camera would handle waiting for the camera to become available.  This allows
 * some of the time necessary to close the camera to happen in parallel with starting the secure
 * camera app.  We can't rely on all third-party camera apps to handle this.  However, an app can
 * put com.android.systemui.statusbar.phone.will_wait_for_camera_available in its meta-data to
 * indicate that it will be responsible for waiting for the camera to become available.
 *
 * It is assumed that the functions in this class, including the constructor, will be called from
 * the UI thread.
 */
public class SecureCameraLaunchManager {
    private static final boolean DEBUG = false;
    private static final String TAG = "SecureCameraLaunchManager";

    // Action sent as a broadcast to tell other apps to stop using the camera.  Other apps that use
    // the camera from keyguard (e.g., Face Unlock) should listen for this broadcast and close the
    // camera as soon as possible after receiving it.
    private static final String CLOSE_CAMERA_ACTION_NAME =
            "com.android.systemui.statusbar.phone.CLOSE_CAMERA";

    // Apps should put this field in their meta-data to indicate that they will take on the
    // responsibility of waiting for the camera to become available.  If this field is present, the
    // SecureCameraLaunchManager launches the secure camera even if the camera hardware has not
    // become available.  Having the secure camera app do the waiting is the optimal approach, but
    // without this field, the SecureCameraLaunchManager doesn't launch the secure camera until the
    // camera hardware is available.
    private static final String META_DATA_WILL_WAIT_FOR_CAMERA_AVAILABLE =
            "com.android.systemui.statusbar.phone.will_wait_for_camera_available";

    // If the camera hardware hasn't become available after this period of time, the
    // SecureCameraLaunchManager launches the secure camera anyway.
    private static final int CAMERA_AVAILABILITY_TIMEOUT_MS = 1000;

    private Context mContext;
    private Handler mHandler;
    private LockPatternUtils mLockPatternUtils;
    private KeyguardBottomAreaView mKeyguardBottomArea;

    private CameraManager mCameraManager;
    private CameraAvailabilityCallback mCameraAvailabilityCallback;
    private Map<String, Boolean> mCameraAvailabilityMap;
    private boolean mWaitingToLaunchSecureCamera;
    private Runnable mLaunchCameraRunnable;

    private class CameraAvailabilityCallback extends CameraManager.AvailabilityCallback {
        @Override
        public void onCameraUnavailable(String cameraId) {
            if (DEBUG) Log.d(TAG, "onCameraUnavailble(" + cameraId + ")");
            mCameraAvailabilityMap.put(cameraId, false);
        }

        @Override
        public void onCameraAvailable(String cameraId) {
            if (DEBUG) Log.d(TAG, "onCameraAvailable(" + cameraId + ")");
            mCameraAvailabilityMap.put(cameraId, true);

            // If we were waiting for the camera hardware to become available to launch the
            // secure camera, we can launch it now if all cameras are available.  If one or more
            // cameras are still not available, we will get this callback again for those
            // cameras.
            if (mWaitingToLaunchSecureCamera && areAllCamerasAvailable()) {
                mKeyguardBottomArea.launchCamera();
                mWaitingToLaunchSecureCamera = false;

                // We no longer need to launch the camera after the timeout hits.
                mHandler.removeCallbacks(mLaunchCameraRunnable);
            }
        }
    }

    public SecureCameraLaunchManager(Context context, KeyguardBottomAreaView keyguardBottomArea) {
        mContext = context;
        mHandler = new Handler();
        mLockPatternUtils = new LockPatternUtils(context);
        mKeyguardBottomArea = keyguardBottomArea;

        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        mCameraAvailabilityCallback = new CameraAvailabilityCallback();

        // An onCameraAvailable() or onCameraUnavailable() callback will be received for each camera
        // when the availability callback is registered, thus initializing the map.
        //
        // Keeping track of the state of all cameras using the onCameraAvailable() and
        // onCameraUnavailable() callbacks can get messy when dealing with hot-pluggable cameras.
        // However, we have a timeout in place such that we will never hang waiting for cameras.
        mCameraAvailabilityMap = new HashMap<String, Boolean>();

        mWaitingToLaunchSecureCamera = false;
        mLaunchCameraRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mWaitingToLaunchSecureCamera) {
                        Log.w(TAG, "Timeout waiting for camera availability");
                        mKeyguardBottomArea.launchCamera();
                        mWaitingToLaunchSecureCamera = false;
                    }
                }
            };
    }

    /**
     * Initializes the SecureCameraManager and starts listening for camera availability.
     */
    public void create() {
        mCameraManager.registerAvailabilityCallback(mCameraAvailabilityCallback, mHandler);
    }

    /**
     * Stops listening for camera availability and cleans up the SecureCameraManager.
     */
    public void destroy() {
        mCameraManager.unregisterAvailabilityCallback(mCameraAvailabilityCallback);
    }

    /**
     * Called when the user is starting to swipe horizontally, possibly to start the secure camera.
     * Although this swipe ultimately may not result in the secure camera opening, we need to stop
     * all other camera usage (e.g., Face Unlock) as soon as possible.  We send out a broadcast to
     * notify other apps that they should close the camera immediately.  The broadcast is sent even
     * if the camera appears to be available, because there could be an app that is about to open
     * the camera.
     */
    public void onSwipingStarted() {
        if (DEBUG) Log.d(TAG, "onSwipingStarted");
        AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent();
                    intent.setAction(CLOSE_CAMERA_ACTION_NAME);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    mContext.sendBroadcast(intent);
                }
            });
    }

    /**
     * Called when the secure camera should be started.  If the camera is available or the secure
     * camera app has indicated that it will wait for camera availability, the secure camera app is
     * launched immediately.  Otherwise, we wait for the camera to become available (or timeout)
     * before launching the secure camera.
     */
    public void startSecureCameraLaunch() {
        if (DEBUG) Log.d(TAG, "startSecureCameraLunch");
        if (areAllCamerasAvailable() || targetWillWaitForCameraAvailable()) {
            mKeyguardBottomArea.launchCamera();
        } else {
            mWaitingToLaunchSecureCamera = true;
            mHandler.postDelayed(mLaunchCameraRunnable, CAMERA_AVAILABILITY_TIMEOUT_MS);
        }
    }

    /**
     * Returns true if all of the cameras we are tracking are currently available.
     */
    private boolean areAllCamerasAvailable() {
        for (boolean cameraAvailable: mCameraAvailabilityMap.values()) {
            if (!cameraAvailable) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines if the secure camera app will wait for the camera hardware to become available
     * before trying to open the camera.  If so, we can fire off an intent to start the secure
     * camera app before the camera is available.  Otherwise, it is our responsibility to wait for
     * the camera hardware to become available before firing off the intent to start the secure
     * camera.
     *
     * Ideally we are able to fire off the secure camera intent as early as possibly so that, if the
     * camera is closing, it can continue to close while the secure camera app is opening.  This
     * improves secure camera startup time.
     */
    private boolean targetWillWaitForCameraAvailable() {
        // Create intent that would launch the secure camera.
        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PackageManager packageManager = mContext.getPackageManager();

        // Get the list of applications that can handle the intent.
        final List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_DEFAULT_ONLY, mLockPatternUtils.getCurrentUser());
        if (appList.size() == 0) {
            if (DEBUG) Log.d(TAG, "No targets found for secure camera intent");
            return false;
        }

        // Get the application that the intent resolves to.
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA,
                mLockPatternUtils.getCurrentUser());

        if (resolved == null || resolved.activityInfo == null) {
            return false;
        }

        // If we would need to launch the resolver activity, then we can't assume that the target
        // is one that would wait for the camera.
        if (wouldLaunchResolverActivity(resolved, appList)) {
            if (DEBUG) Log.d(TAG, "Secure camera intent would launch resolver");
            return false;
        }

        // If the target doesn't have meta-data we must assume it won't wait for the camera.
        if (resolved.activityInfo.metaData == null || resolved.activityInfo.metaData.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No meta-data found for secure camera application");
            return false;
        }

        // Check the secure camera app meta-data to see if it indicates that it will wait for the
        // camera to become available.
        boolean willWaitForCameraAvailability =
                resolved.activityInfo.metaData.getBoolean(META_DATA_WILL_WAIT_FOR_CAMERA_AVAILABLE);

        if (DEBUG) Log.d(TAG, "Target will wait for camera: " + willWaitForCameraAvailability);

        return willWaitForCameraAvailability;
    }

    /**
     * Determines if the activity that would be launched by the intent is the ResolverActivity.
     */
    private boolean wouldLaunchResolverActivity(ResolveInfo resolved, List<ResolveInfo> appList) {
        // If the list contains the resolved activity, then it can't be the ResolverActivity itself.
        for (int i = 0; i < appList.size(); i++) {
            ResolveInfo tmp = appList.get(i);
            if (tmp.activityInfo.name.equals(resolved.activityInfo.name)
                    && tmp.activityInfo.packageName.equals(resolved.activityInfo.packageName)) {
                return false;
            }
        }
        return true;
    }
}
