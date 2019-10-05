/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.os.DeviceIdleManager;
import android.os.IDeviceIdleController;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * This class needs to be pre-loaded by zygote.  This is where the device idle manager wrapper
 * is registered.
 *
 * @hide
 */
public class DeviceIdleFrameworkInitializer {
    private static IDeviceIdleController sIDeviceIdleController;

    static {
        SystemServiceRegistry.registerCachedService(
                Context.DEVICE_IDLE_CONTROLLER, DeviceIdleManager.class,
                (context, b) -> new DeviceIdleManager(
                        context, IDeviceIdleController.Stub.asInterface(b)));
        PowerManager.setIsIgnoringBatteryOptimizationsCallback((packageName) -> {
            // No need for synchronization on sIDeviceIdleController; worst case
            // we just initialize it twice.
            if (sIDeviceIdleController == null) {
                sIDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            }
            try {
                return sIDeviceIdleController.isPowerSaveWhitelistApp(packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        });
    }
}
