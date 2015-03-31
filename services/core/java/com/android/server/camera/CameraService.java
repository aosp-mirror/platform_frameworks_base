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

import android.content.Context;
import android.hardware.ICameraService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.SystemService;

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

    // Event arguments to use with the camera service notifySystemEvent call:
    public static final int NO_EVENT = 0; // NOOP
    public static final int USER_SWITCHED = 1; // User changed, argument is the new user handle

    public CameraService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {}

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        /**
         * Forward the user switch event to the native camera service running in mediaserver.
         */
        IBinder cameraServiceBinder = getBinderService(CAMERA_SERVICE_BINDER_NAME);
        if (cameraServiceBinder == null) {
            return; // Camera service not active, there is no need to evict user clients.
        }
        ICameraService cameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);
        try {
            cameraServiceRaw.notifySystemEvent(USER_SWITCHED, userHandle);
        } catch (RemoteException e) {
            // Do nothing, if camera service is dead, there is no need to evict user clients.
        }
    }
}
