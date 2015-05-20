/*
 * Copyright 2015 The Android Open Source Project
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
package com.android.server.camera;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceProxy;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserManager;

import com.android.server.SystemService;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CameraService is the system_server analog to the camera service running in mediaserver.
 *
 * @hide
 */
public class CameraService extends SystemService {

    /**
     * This must match the ICameraService.aidl definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

    public static final String CAMERA_SERVICE_PROXY_BINDER_NAME = "media.camera.proxy";

    // Event arguments to use with the camera service notifySystemEvent call:
    public static final int NO_EVENT = 0; // NOOP
    public static final int USER_SWITCHED = 1; // User changed, argument is the new user handle

    private final Context mContext;
    private UserManager mUserManager;

    private final Object mLock = new Object();
    private Set<Integer> mEnabledCameraUsers;

    private final ICameraServiceProxy.Stub mCameraServiceProxy = new ICameraServiceProxy.Stub() {
        @Override
        public void pingForUserUpdate() {
            // Binder call
            synchronized(mLock) {
                if (mEnabledCameraUsers != null) {
                    notifyMediaserver(USER_SWITCHED, mEnabledCameraUsers);
                }
            }
        }
    };

    public CameraService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        mUserManager = UserManager.get(mContext);
        if (mUserManager == null) {
            // Should never see this unless someone messes up the SystemServer service boot order.
            throw new IllegalStateException("UserManagerService must start before CameraService!");
        }
        publishBinderService(CAMERA_SERVICE_PROXY_BINDER_NAME, mCameraServiceProxy);
    }

    @Override
    public void onStartUser(int userHandle) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                // Initialize mediaserver, or update mediaserver if we are recovering from a crash.
                switchUserLocked(userHandle);
            }
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        synchronized(mLock) {
            switchUserLocked(userHandle);
        }
    }

    private void switchUserLocked(int userHandle) {
        Set<Integer> currentUserHandles = getEnabledUserHandles(userHandle);
        if (mEnabledCameraUsers == null || !mEnabledCameraUsers.equals(currentUserHandles)) {
            // Some user handles have been added or removed, update mediaserver.
            mEnabledCameraUsers = currentUserHandles;
            notifyMediaserver(USER_SWITCHED, currentUserHandles);
        }
    }

    private Set<Integer> getEnabledUserHandles(int currentUserHandle) {
        List<UserInfo> userProfiles = mUserManager.getEnabledProfiles(currentUserHandle);
        Set<Integer> handles = new HashSet<>(userProfiles.size());

        for (UserInfo i : userProfiles) {
            handles.add(i.id);
        }

        return handles;
    }

    private void notifyMediaserver(int eventType, Set<Integer> updatedUserHandles) {
        // Forward the user switch event to the native camera service running in the mediaserver
        // process.
        IBinder cameraServiceBinder = getBinderService(CAMERA_SERVICE_BINDER_NAME);
        if (cameraServiceBinder == null) {
            return; // Camera service not active, cannot evict user clients.
        }

        ICameraService cameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);

        try {
            cameraServiceRaw.notifySystemEvent(eventType, toArray(updatedUserHandles));
        } catch (RemoteException e) {
            // Not much we can do if camera service is dead.
        }
    }

    private static int[] toArray(Collection<Integer> c) {
        int len = c.size();
        int[] ret = new int[len];
        int idx = 0;
        for (Integer i : c) {
            ret[idx++] = i;
        }
        return ret;
    }
}
