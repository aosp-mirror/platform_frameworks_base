/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;

/**
 * @hide
 */
@SystemService(Context.DREAM_SERVICE)
@TestApi
public class DreamManager {
    private final IDreamManager mService;
    private final Context mContext;

    /**
     * @hide
     */
    public DreamManager(Context context) throws ServiceManager.ServiceNotFoundException {
        mService = IDreamManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(DreamService.DREAM_SERVICE));
        mContext = context;
    }

    /**
     * Starts dream service with name "name".
     *
     * <p>This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void startDream(@NonNull ComponentName name) {
        try {
            mService.dream();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the dream service on the device if one is started.
     *
     * <p> This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void stopDream() {
        try {
            mService.awaken();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the active dream on the device to be "dreamComponent".
     *
     * <p>This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setActiveDream(@NonNull ComponentName dreamComponent) {
        ComponentName[] dreams = {dreamComponent};
        try {
            mService.setDreamComponentsForUser(mContext.getUserId(), dreams);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the device is Dreaming.
     *
     * <p> This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean isDreaming() {
        try {
            return mService.isDreaming();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }
}
