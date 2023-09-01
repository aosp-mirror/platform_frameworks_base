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

package com.android.server.companion.virtual;

import static android.hardware.camera2.CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_UNSUPPORTED;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraInjectionSession;
import android.hardware.camera2.CameraManager;
import android.os.Process;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.Set;

/**
 * Handles blocking access to the camera for apps running on virtual devices.
 */
class CameraAccessController extends CameraManager.AvailabilityCallback implements AutoCloseable {
    private static final String TAG = "CameraAccessController";

    private final Object mLock = new Object();
    private final Object mObserverLock = new Object();

    private final Context mContext;
    private final VirtualDeviceManagerInternal mVirtualDeviceManagerInternal;
    private final CameraAccessBlockedCallback mBlockedCallback;
    private final CameraManager mCameraManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;

    @GuardedBy("mObserverLock")
    private int mObserverCount = 0;

    @GuardedBy("mLock")
    private ArrayMap<String, InjectionSessionData> mPackageToSessionData = new ArrayMap<>();

    /**
     * Mapping from camera ID to open camera app associations. Key is the camera id, value is the
     * information of the app's uid and package name.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, OpenCameraInfo> mAppsToBlockOnVirtualDevice = new ArrayMap<>();

    static class InjectionSessionData {
        public int appUid;
        public ArrayMap<String, CameraInjectionSession> cameraIdToSession = new ArrayMap<>();
    }

    static class OpenCameraInfo {
        public String packageName;
        public Set<Integer> packageUids;
    }

    interface CameraAccessBlockedCallback {
        /**
         * Called whenever an app was blocked from accessing a camera.
         * @param appUid uid for the app which was blocked
         */
        void onCameraAccessBlocked(int appUid);
    }

    CameraAccessController(Context context,
            VirtualDeviceManagerInternal virtualDeviceManagerInternal,
            CameraAccessBlockedCallback blockedCallback) {
        mContext = context;
        mVirtualDeviceManagerInternal = virtualDeviceManagerInternal;
        mBlockedCallback = blockedCallback;
        mCameraManager = mContext.getSystemService(CameraManager.class);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
    }

    /**
     * Returns the userId for which the camera access should be blocked.
     */
    @UserIdInt
    public int getUserId() {
        return mContext.getUserId();
    }

    /**
     * Returns the number of observers currently relying on this controller.
     */
    public int getObserverCount() {
        synchronized (mObserverLock) {
            return mObserverCount;
        }
    }

    /**
     * Starts watching for camera access by uids running on a virtual device, if we were not
     * already doing so.
     */
    public void startObservingIfNeeded() {
        synchronized (mObserverLock) {
            if (mObserverCount == 0) {
                mCameraManager.registerAvailabilityCallback(mContext.getMainExecutor(), this);
            }
            mObserverCount++;
        }
    }

    /**
     * Stop watching for camera access.
     */
    public void stopObservingIfNeeded() {
        synchronized (mObserverLock) {
            mObserverCount--;
            if (mObserverCount <= 0) {
                close();
            }
        }
    }

    /**
     * Need to block camera access for applications running on virtual displays.
     * <p>
     * Apps that open the camera on the main display will need to block camera access if moved to a
     * virtual display.
     *
     * @param runningUids uids of the application running on the virtual display
     */
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void blockCameraAccessIfNeeded(Set<Integer> runningUids) {
        synchronized (mLock) {
            for (int i = 0; i < mAppsToBlockOnVirtualDevice.size(); i++) {
                final String cameraId = mAppsToBlockOnVirtualDevice.keyAt(i);
                final OpenCameraInfo openCameraInfo = mAppsToBlockOnVirtualDevice.get(cameraId);
                final String packageName = openCameraInfo.packageName;
                for (int packageUid : openCameraInfo.packageUids) {
                    if (runningUids.contains(packageUid)) {
                        InjectionSessionData data = mPackageToSessionData.get(packageName);
                        if (data == null) {
                            data = new InjectionSessionData();
                            data.appUid = packageUid;
                            mPackageToSessionData.put(packageName, data);
                        }
                        startBlocking(packageName, cameraId);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (mObserverLock) {
            if (mObserverCount < 0) {
                Slog.wtf(TAG, "Unexpected negative mObserverCount: " + mObserverCount);
            } else if (mObserverCount > 0) {
                Slog.w(TAG, "Unexpected close with observers remaining: " + mObserverCount);
            }
        }
        mCameraManager.unregisterAvailabilityCallback(this);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void onCameraOpened(@NonNull String cameraId, @NonNull String packageName) {
        synchronized (mLock) {
            InjectionSessionData data = mPackageToSessionData.get(packageName);
            List<UserInfo> aliveUsers = mUserManager.getAliveUsers();
            ArraySet<Integer> packageUids = new ArraySet<>();
            for (UserInfo user : aliveUsers) {
                int userId = user.getUserHandle().getIdentifier();
                int appUid = queryUidFromPackageName(userId, packageName);
                if (mVirtualDeviceManagerInternal.isAppRunningOnAnyVirtualDevice(appUid)) {
                    if (data == null) {
                        data = new InjectionSessionData();
                        data.appUid = appUid;
                        mPackageToSessionData.put(packageName, data);
                    }
                    if (data.cameraIdToSession.containsKey(cameraId)) {
                        return;
                    }
                    startBlocking(packageName, cameraId);
                    return;
                } else {
                    if (appUid != Process.INVALID_UID) {
                        packageUids.add(appUid);
                    }
                }
            }
            OpenCameraInfo openCameraInfo = new OpenCameraInfo();
            openCameraInfo.packageName = packageName;
            openCameraInfo.packageUids = packageUids;
            mAppsToBlockOnVirtualDevice.put(cameraId, openCameraInfo);
            CameraInjectionSession existingSession =
                    (data != null) ? data.cameraIdToSession.get(cameraId) : null;
            if (existingSession != null) {
                existingSession.close();
                data.cameraIdToSession.remove(cameraId);
                if (data.cameraIdToSession.isEmpty()) {
                    mPackageToSessionData.remove(packageName);
                }
            }
        }
    }

    @Override
    public void onCameraClosed(@NonNull String cameraId) {
        synchronized (mLock) {
            mAppsToBlockOnVirtualDevice.remove(cameraId);
            for (int i = mPackageToSessionData.size() - 1; i >= 0; i--) {
                InjectionSessionData data = mPackageToSessionData.valueAt(i);
                CameraInjectionSession session = data.cameraIdToSession.get(cameraId);
                if (session != null) {
                    session.close();
                    data.cameraIdToSession.remove(cameraId);
                    if (data.cameraIdToSession.isEmpty()) {
                        mPackageToSessionData.removeAt(i);
                    }
                }
            }
        }
    }

    /**
     * Turns on blocking for a particular camera and package.
     */
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    private void startBlocking(String packageName, String cameraId) {
        try {
            Slog.d(
                    TAG,
                    "startBlocking() cameraId: " + cameraId + " packageName: " + packageName);
            mCameraManager.injectCamera(packageName, cameraId, /* externalCamId */ "",
                    mContext.getMainExecutor(),
                    new CameraInjectionSession.InjectionStatusCallback() {
                        @Override
                        public void onInjectionSucceeded(
                                @NonNull CameraInjectionSession session) {
                            CameraAccessController.this.onInjectionSucceeded(cameraId, packageName,
                                    session);
                        }

                        @Override
                        public void onInjectionError(@NonNull int errorCode) {
                            CameraAccessController.this.onInjectionError(cameraId, packageName,
                                    errorCode);
                        }
                    });
        } catch (CameraAccessException e) {
            Slog.e(TAG,
                    "Failed to injectCamera for cameraId:" + cameraId + " package:" + packageName,
                    e);
        }
    }

    private void onInjectionSucceeded(String cameraId, String packageName,
            @NonNull CameraInjectionSession session) {
        synchronized (mLock) {
            InjectionSessionData data = mPackageToSessionData.get(packageName);
            if (data == null) {
                Slog.e(TAG, "onInjectionSucceeded didn't find expected entry for package "
                        + packageName);
                session.close();
                return;
            }
            CameraInjectionSession existingSession = data.cameraIdToSession.put(cameraId, session);
            if (existingSession != null) {
                Slog.e(TAG, "onInjectionSucceeded found unexpected existing session for camera "
                        + cameraId);
                existingSession.close();
            }
        }
    }

    private void onInjectionError(String cameraId, String packageName, @NonNull int errorCode) {
        if (errorCode != ERROR_INJECTION_UNSUPPORTED) {
            // ERROR_INJECTION_UNSUPPORTED means that there wasn't an external camera to map to the
            // internal camera, which is expected when using the injection interface as we are in
            // this class to simply block camera access. Any other error is unexpected.
            Slog.e(TAG, "Unexpected injection error code:" + errorCode + " for camera:" + cameraId
                    + " and package:" + packageName);
            return;
        }
        synchronized (mLock) {
            InjectionSessionData data = mPackageToSessionData.get(packageName);
            if (data != null) {
                mBlockedCallback.onCameraAccessBlocked(data.appUid);
            }
        }
    }

    private int queryUidFromPackageName(int userId, String packageName) {
        try {
            final ApplicationInfo ainfo =
                    mPackageManager.getApplicationInfoAsUser(packageName,
                        PackageManager.GET_ACTIVITIES, userId);
            return ainfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "queryUidFromPackageName - unknown package " + packageName, e);
            return Process.INVALID_UID;
        }
    }
}
