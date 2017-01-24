/*
 * Copyright 2016 The Android Open Source Project
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

package android.os;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.os.SystemProperties;
import android.util.Log;

import dalvik.system.VMRuntime;

import java.io.File;

/** @hide */
public final class GraphicsEnvironment {

    private static final boolean DEBUG = false;
    private static final String TAG = "GraphicsEnvironment";
    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";

    public static void setupGraphicsEnvironment(Context context) {
        chooseDriver(context);

        // Now that we've figured out which driver to use for this process, load and initialize it.
        // This can take multiple frame periods, and it would otherwise happen as part of the first
        // frame, increasing first-frame latency. Starting it here, as a low-priority background
        // thread, means that it's usually done long before we start drawing the first frame,
        // without significantly disrupting other activity launch work.
        Thread eglInitThread = new Thread(
                () -> {
                    EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                },
                "EGL Init");
        eglInitThread.start();
    }

    private static void chooseDriver(Context context) {
        String driverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        if (driverPackageName == null || driverPackageName.isEmpty()) {
            return;
        }
        // To minimize risk of driver updates crippling the device beyond user repair, never use an
        // updated driver for privileged or non-updated system apps. Presumably pre-installed apps
        // were tested thoroughly with the pre-installed driver.
        ApplicationInfo ai = context.getApplicationInfo();
        if (ai.isPrivilegedApp() || (ai.isSystemApp() && !ai.isUpdatedSystemApp())) {
            if (DEBUG) Log.v(TAG, "ignoring driver package for privileged/non-updated system app");
            return;
        }
        ApplicationInfo driverInfo;
        try {
            driverInfo = context.getPackageManager().getApplicationInfo(driverPackageName,
                    PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            return;
        }
        String abi = chooseAbi(driverInfo);
        if (abi == null) {
            if (DEBUG) {
                // This is the normal case for the pre-installed empty driver package, don't spam
                if (driverInfo.isUpdatedSystemApp()) {
                    Log.w(TAG, "updated driver package has no compatible native libraries");
                }
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(driverInfo.nativeLibraryDir)
          .append(File.pathSeparator);
        sb.append(driverInfo.sourceDir)
          .append("!/lib/")
          .append(abi);
        String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "gfx driver package libs: " + paths);
        setDriverPath(paths);
    }

    private static String chooseAbi(ApplicationInfo ai) {
        String isa = VMRuntime.getCurrentInstructionSet();
        if (ai.primaryCpuAbi != null &&
                isa.equals(VMRuntime.getInstructionSet(ai.primaryCpuAbi))) {
            return ai.primaryCpuAbi;
        }
        if (ai.secondaryCpuAbi != null &&
                isa.equals(VMRuntime.getInstructionSet(ai.secondaryCpuAbi))) {
            return ai.secondaryCpuAbi;
        }
        return null;
    }

    private static native void setDriverPath(String path);

}
