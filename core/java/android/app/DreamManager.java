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

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
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
     * Returns whether Settings.Secure.SCREENSAVER_ENABLED is enabled.
     *
     * @hide
     */
    @TestApi
    public boolean isScreensaverEnabled() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
    }

    /**
     * Sets whether Settings.Secure.SCREENSAVER_ENABLED is enabled.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void setScreensaverEnabled(boolean enabled) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED, enabled ? 1 : 0, UserHandle.USER_CURRENT);
    }

    /**
     * Returns whether dreams are supported.
     *
     * @hide
     */
    @TestApi
    public boolean areDreamsSupported() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsSupported);
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
    public void setActiveDream(@Nullable ComponentName dreamComponent) {
        ComponentName[] dreams = {dreamComponent};

        try {
            mService.setDreamComponentsForUser(mContext.getUserId(),
                    dreamComponent != null ? dreams : null);
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
    public void setDreamOverlay(@Nullable ComponentName dreamOverlayComponent) {
        try {
            mService.registerDreamOverlayService(dreamOverlayComponent);
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
