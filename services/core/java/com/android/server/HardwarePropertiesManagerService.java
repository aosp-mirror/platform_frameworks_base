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

import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY;
import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU;
import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU;
import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT;
import static android.os.HardwarePropertiesManager.TEMPERATURE_SHUTDOWN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_THROTTLING;
import static android.os.HardwarePropertiesManager.TEMPERATURE_THROTTLING_BELOW_VR_MIN;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.CpuUsageInfo;
import android.os.IHardwarePropertiesManager;
import android.os.UserHandle;

import com.android.internal.util.DumpUtils;
import com.android.server.vr.VrManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Service for {@link HardwarePropertiesManager}
 */
public class HardwarePropertiesManagerService extends IHardwarePropertiesManager.Stub {

    private static final String TAG = "HardwarePropertiesManagerService";
    private static native void nativeInit();

    private static native float[] nativeGetFanSpeeds();
    private static native float[] nativeGetDeviceTemperatures(int type, int source);
    private static native CpuUsageInfo[] nativeGetCpuUsages();

    private final Context mContext;
    private final Object mLock = new Object();
    private final AppOpsManager mAppOps;

    public HardwarePropertiesManagerService(Context context) {
        mContext = context;
        mAppOps = (AppOpsManager)mContext.getSystemService(Context.APP_OPS_SERVICE);
        synchronized (mLock) {
            nativeInit();
        }
    }

    // TODO - Make HardwarePropertiesManager APIs require a userId to verifiy
    // cross user permission - b/63697518
    @Override
    public float[] getDeviceTemperatures(String callingPackage, int type, int source)
            throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetDeviceTemperatures(type, source);
        }
    }

    // TODO - Make HardwarePropertiesManager APIs require a userId to verifiy
    // cross user permission - b/63697518
    @Override
    public CpuUsageInfo[] getCpuUsages(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetCpuUsages();
        }
    }

    // TODO - Make HardwarePropertiesManager APIs require a userId to verifiy
    // cross user permission - b/63697518
    @Override
    public float[] getFanSpeeds(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetFanSpeeds();
        }
    }

    private String getCallingPackageName() {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(
                Binder.getCallingUid());
        if (packages != null && packages.length > 0) {
           return packages[0];
        }
        return "unknown";
    }

    private void dumpTempValues(String pkg, PrintWriter pw, int type,
            String typeLabel) {
        dumpTempValues(pkg, pw, type, typeLabel, "temperatures: ",
                TEMPERATURE_CURRENT);
        dumpTempValues(pkg, pw, type, typeLabel, "throttling temperatures: ",
                TEMPERATURE_THROTTLING);
        dumpTempValues(pkg, pw, type, typeLabel, "shutdown temperatures: ",
                TEMPERATURE_SHUTDOWN);
        dumpTempValues(pkg, pw, type, typeLabel, "vr throttling temperatures: ",
                TEMPERATURE_THROTTLING_BELOW_VR_MIN);
    }

    private void dumpTempValues(String pkg, PrintWriter pw, int type,
            String typeLabel, String subLabel, int valueType) {
        pw.println(typeLabel + subLabel + Arrays.toString(getDeviceTemperatures(
                pkg, type, valueType)));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.println("****** Dump of HardwarePropertiesManagerService ******");

        final String PKG = getCallingPackageName();
        dumpTempValues(PKG, pw, DEVICE_TEMPERATURE_CPU, "CPU ");
        dumpTempValues(PKG, pw, DEVICE_TEMPERATURE_GPU, "GPU ");
        dumpTempValues(PKG, pw, DEVICE_TEMPERATURE_BATTERY, "Battery ");
        dumpTempValues(PKG, pw, DEVICE_TEMPERATURE_SKIN, "Skin ");

        float[] fanSpeeds = getFanSpeeds(PKG);
        pw.println("Fan speed: " + Arrays.toString(fanSpeeds) + "\n");

        CpuUsageInfo[] cpuUsageInfos = getCpuUsages(PKG);
        int core = 0;
        for (int i = 0; i < cpuUsageInfos.length; i++) {
            pw.println("Cpu usage of core: " + i +
                    ", active = " + cpuUsageInfos[i].getActive() +
                    ", total = " + cpuUsageInfos[i].getTotal());
        }
        pw.println("****** End of HardwarePropertiesManagerService dump ******");
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
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);
        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(callingPackage)
                && mContext.checkCallingOrSelfPermission(Manifest.permission.DEVICE_POWER)
                        != PackageManager.PERMISSION_GRANTED
                && (vrService == null || !vrService.isCurrentVrListener(callingPackage, userId))) {
            throw new SecurityException("The caller is neither a device owner"
                + ", nor holding the DEVICE_POWER permission, nor the current VrListener.");
        }
    }
}


