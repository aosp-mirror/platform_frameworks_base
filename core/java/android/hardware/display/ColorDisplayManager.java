/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.display;

import android.Manifest;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import com.android.internal.R;

/**
 * Manages the display's color transforms and modes.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.COLOR_DISPLAY_SERVICE)
public final class ColorDisplayManager {

    private final ColorDisplayManagerInternal mManager;

    /**
     * @hide
     */
    public ColorDisplayManager() {
        mManager = ColorDisplayManagerInternal.getInstance();
    }

    /**
     * Returns whether the device has a wide color gamut display.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean isDeviceColorManaged() {
        return mManager.isDeviceColorManaged();
    }

    /**
     * Set the level of color saturation to apply to the display.
     *
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setSaturationLevel(@IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setSaturationLevel(saturationLevel);
    }

    /**
     * Set the level of color saturation to apply to a specific app.
     *
     * @param packageName the package name of the app whose windows should be desaturated
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setAppSaturationLevel(@NonNull String packageName,
            @IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setAppSaturationLevel(packageName, saturationLevel);
    }

    /**
     * Returns {@code true} if Night Display is supported by the device.
     *
     * @hide
     */
    public static boolean isNightDisplayAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_nightDisplayAvailable);
    }

    /**
     * Returns {@code true} if display white balance is supported by the device.
     *
     * @hide
     */
    public static boolean isDisplayWhiteBalanceAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_displayWhiteBalanceAvailable);
    }

    /**
     * Check if the color transforms are color accelerated. Some transforms are experimental only
     * on non-accelerated platforms due to the performance implications.
     *
     * @hide
     */
    public static boolean isColorTransformAccelerated(Context context) {
        return context.getResources().getBoolean(R.bool.config_setColorTransformAccelerated);
    }

    private static class ColorDisplayManagerInternal {

        private static ColorDisplayManagerInternal sInstance;

        private final IColorDisplayManager mCdm;

        private ColorDisplayManagerInternal(IColorDisplayManager colorDisplayManager) {
            mCdm = colorDisplayManager;
        }

        public static ColorDisplayManagerInternal getInstance() {
            synchronized (ColorDisplayManagerInternal.class) {
                if (sInstance == null) {
                    try {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.COLOR_DISPLAY_SERVICE);
                        sInstance = new ColorDisplayManagerInternal(
                                IColorDisplayManager.Stub.asInterface(b));
                    } catch (ServiceNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return sInstance;
            }
        }

        boolean isDeviceColorManaged() {
            try {
                return mCdm.isDeviceColorManaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setSaturationLevel(int saturationLevel) {
            try {
                return mCdm.setSaturationLevel(saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setAppSaturationLevel(String packageName, int saturationLevel) {
            try {
                return mCdm.setAppSaturationLevel(packageName, saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
