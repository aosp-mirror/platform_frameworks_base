/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server;

import android.Manifest;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.CpuUsageInfo;
import android.os.IHardwarePropertiesManager;
import android.os.Process;
import android.os.UserHandle;
import com.android.server.vr.VrManagerInternal;

import java.util.Arrays;

/**
 * Service for {@link HardwarePropertiesManager}
 */
public class HardwarePropertiesManagerService extends IHardwarePropertiesManager.Stub {

    private static native void nativeInit();

    private static native float[] nativeGetFanSpeeds();
    private static native float[] nativeGetDeviceTemperatures(int type, int source);
    private static native CpuUsageInfo[] nativeGetCpuUsages();

    private final Context mContext;
    private final Object mLock = new Object();

    public HardwarePropertiesManagerService(Context context) {
        mContext = context;
        synchronized (mLock) {
            nativeInit();
        }
    }

    @Override
    public float[] getDeviceTemperatures(String callingPackage, int type, int source)
            throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetDeviceTemperatures(type, source);
        }
    }

    @Override
    public CpuUsageInfo[] getCpuUsages(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetCpuUsages();
        }
    }

    @Override
    public float[] getFanSpeeds(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetFanSpeeds();
        }
    }

    /**
     * Throws SecurityException if the calling package is not allowed to retrieve information
     * provided by the service.
     *
     * @param callingPackage The calling package name.
     *
     * @throws SecurityException if something other than the device owner, the current VR service,
     *         or a caller holding the {@link Manifest.permission#DEVICE_POWER} permission tries to
     *         retrieve information provided by this service.
     */
    private void enforceHardwarePropertiesRetrievalAllowed(String callingPackage)
            throws SecurityException {
        final PackageManager pm = mContext.getPackageManager();
        int uid = 0;
        try {
            uid = pm.getPackageUid(callingPackage, 0);
            if (Binder.getCallingUid() != uid) {
                throw new SecurityException("The caller has faked the package name.");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("The caller has faked the package name.");
        }

        final int userId = UserHandle.getUserId(uid);
        final VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);
        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(callingPackage)
                && !vrService.isCurrentVrListener(callingPackage, userId)
                && mContext.checkCallingOrSelfPermission(Manifest.permission.DEVICE_POWER)
                        != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("The caller is not a device owner, bound VrListenerService"
                + ", or holding the DEVICE_POWER permission.");
        }
    }
}
