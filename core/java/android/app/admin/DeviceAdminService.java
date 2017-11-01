/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.app.admin;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * Base class for a service that device owner/profile owners can optionally have.
 *
 * <p>The system searches for it with an intent filter with the
 * {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE} action, and tries to keep a bound
 * connection as long as the hosting user is running, so that the device/profile owner is always
 * considered to be in the foreground.  This is useful to receive implicit broadcasts that
 * can no longer be received by manifest receivers by apps targeting Android version
 * {@link android.os.Build.VERSION_CODES#O}.  Device/profile owners can use a runtime-registered
 * broadcast receiver instead, and have a {@link DeviceAdminService} so that the process is always
 * running.
 *
 * <p>Device/profile owners can use
 * {@link android.content.pm.PackageManager#setComponentEnabledSetting(ComponentName, int, int)}
 * to disable/enable its own service.  For example, when a device/profile owner no longer needs
 * to be in the foreground, it can (and should) disable its service.
 *
 * <p>The service must be protected with the permission
 * {@link android.Manifest.permission#BIND_DEVICE_ADMIN}.  Otherwise the system would ignore it.
 *
 * <p>When the owner process crashes, the service will be re-bound automatically after a
 * back-off.
 *
 * <p>Note the process may still be killed if the system is under heavy memory pressure, in which
 * case the process will be re-started later.
 */
public class DeviceAdminService extends Service {
    private final IDeviceAdminServiceImpl mImpl;

    public DeviceAdminService() {
        mImpl = new IDeviceAdminServiceImpl();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mImpl.asBinder();
    }

    private class IDeviceAdminServiceImpl extends IDeviceAdminService.Stub {
    }

    // So far, we have no methods in this class.
}
