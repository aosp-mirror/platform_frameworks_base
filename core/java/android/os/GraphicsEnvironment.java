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
import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** @hide */
public class GraphicsEnvironment {

    private static final GraphicsEnvironment sInstance = new GraphicsEnvironment();

    /**
     * Returns the shared {@link GraphicsEnvironment} instance.
     */
    public static GraphicsEnvironment getInstance() {
        return sInstance;
    }

    private static final boolean DEBUG = false;
    private static final String TAG = "GraphicsEnvironment";
    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";
    private static final String GAME_DRIVER_WHITELIST_ALL = "*";

    private ClassLoader mClassLoader;
    private String mLayerPath;
    private String mDebugLayerPath;

    /**
     * Set up GraphicsEnvironment
     */
    public void setup(Context context, Bundle coreSettings) {
        setupGpuLayers(context);
        chooseDriver(context, coreSettings);
    }

    /**
     * Check whether application is debuggable
     */
    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) > 0;
    }

    /**
     * Store the layer paths available to the loader.
     */
    public void setLayerPaths(ClassLoader classLoader,
                              String layerPath,
                              String debugLayerPath) {
        // We have to store these in the class because they are set up before we
        // have access to the Context to properly set up GraphicsEnvironment
        mClassLoader = classLoader;
        mLayerPath = layerPath;
        mDebugLayerPath = debugLayerPath;
    }

    /**
     * Set up layer search paths for all apps
     * If debuggable, check for additional debug settings
     */
    private void setupGpuLayers(Context context) {

        String layerPaths = "";

        // Only enable additional debug functionality if the following conditions are met:
        // 1. App is debuggable
        // 2. ENABLE_GPU_DEBUG_LAYERS is true
        // 3. Package name is equal to GPU_DEBUG_APP

        if (isDebuggable(context)) {

            final int enable = Settings.Global.getInt(context.getContentResolver(),
                                                      Settings.Global.ENABLE_GPU_DEBUG_LAYERS, 0);

            if (enable != 0) {

                final String gpuDebugApp = Settings.Global.getString(context.getContentResolver(),
                                                                     Settings.Global.GPU_DEBUG_APP);

                final String packageName = context.getPackageName();

                if ((gpuDebugApp != null && packageName != null)
                        && (!gpuDebugApp.isEmpty() && !packageName.isEmpty())
                        && gpuDebugApp.equals(packageName)) {
                    Log.i(TAG, "GPU debug layers enabled for " + packageName);

                    // Prepend the debug layer path as a searchable path.
                    // This will ensure debug layers added will take precedence over
                    // the layers specified by the app.
                    layerPaths = mDebugLayerPath + ":";

                    String layers = Settings.Global.getString(context.getContentResolver(),
                                                              Settings.Global.GPU_DEBUG_LAYERS);

                    Log.i(TAG, "Debug layer list: " + layers);
                    if (layers != null && !layers.isEmpty()) {
                        setDebugLayers(layers);
                    }
                }
            }

        }

        // Include the app's lib directory in all cases
        layerPaths += mLayerPath;

        setLayerPaths(mClassLoader, layerPaths);
    }

    private static List<String> getGlobalSettingsString(Bundle bundle, String globalSetting) {
        List<String> valueList = null;
        final String settingsValue = bundle.getString(globalSetting);

        if (settingsValue != null) {
            valueList = new ArrayList<>(Arrays.asList(settingsValue.split(",")));
        } else {
            valueList = new ArrayList<>();
        }

        return valueList;
    }

    /**
     * Choose whether the current process should use the builtin or an updated driver.
     */
    private static void chooseDriver(Context context, Bundle coreSettings) {
        final String driverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        if (driverPackageName == null || driverPackageName.isEmpty()) {
            return;
        }

        final ApplicationInfo driverInfo;
        try {
            driverInfo = context.getPackageManager().getApplicationInfo(driverPackageName,
                    PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            return;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        if (driverInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Log.w(TAG, "updated driver package is not known to be compatible with O");
            }
            return;
        }

        // To minimize risk of driver updates crippling the device beyond user repair, never use an
        // updated driver for privileged or non-updated system apps. Presumably pre-installed apps
        // were tested thoroughly with the pre-installed driver.
        final ApplicationInfo ai = context.getApplicationInfo();
        if (ai.isPrivilegedApp() || (ai.isSystemApp() && !ai.isUpdatedSystemApp())) {
            if (DEBUG) Log.v(TAG, "ignoring driver package for privileged/non-updated system app");
            return;
        }

        // GAME_DRIVER_ALL_APPS
        // 0: Default (Invalid values fallback to default as well)
        // 1: All apps use Game Driver
        // 2: All apps use system graphics driver
        final int gameDriverAllApps = coreSettings.getInt(Settings.Global.GAME_DRIVER_ALL_APPS, 0);
        if (gameDriverAllApps == 2) {
            if (DEBUG) {
                Log.w(TAG, "Game Driver is turned off on this device");
            }
            return;
        }

        if (gameDriverAllApps != 1) {
            // GAME_DRIVER_OPT_OUT_APPS has higher priority than GAME_DRIVER_OPT_IN_APPS
            if (getGlobalSettingsString(coreSettings, Settings.Global.GAME_DRIVER_OPT_OUT_APPS)
                            .contains(ai.packageName)) {
                if (DEBUG) {
                    Log.w(TAG, ai.packageName + " opts out from Game Driver.");
                }
                return;
            }
            final boolean isOptIn =
                    getGlobalSettingsString(coreSettings, Settings.Global.GAME_DRIVER_OPT_IN_APPS)
                            .contains(ai.packageName);
            final List<String> whitelist = getGlobalSettingsString(coreSettings,
                    Settings.Global.GAME_DRIVER_WHITELIST);
            if (!isOptIn && whitelist.indexOf(GAME_DRIVER_WHITELIST_ALL) != 0
                    && !whitelist.contains(ai.packageName)) {
                if (DEBUG) {
                    Log.w(TAG, ai.packageName + " is not on the whitelist.");
                }
                return;
            }

            // If the application is not opted-in and check whether it's on the blacklist,
            // terminate early if it's on the blacklist and fallback to system driver.
            if (!isOptIn
                    && getGlobalSettingsString(coreSettings, Settings.Global.GAME_DRIVER_BLACKLIST)
                            .contains(ai.packageName)) {
                return;
            }
        }

        final String abi = chooseAbi(driverInfo);
        if (abi == null) {
            if (DEBUG) {
                // This is the normal case for the pre-installed empty driver package, don't spam
                if (driverInfo.isUpdatedSystemApp()) {
                    Log.w(TAG, "updated driver package has no compatible native libraries");
                }
            }
            return;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(driverInfo.nativeLibraryDir)
          .append(File.pathSeparator);
        sb.append(driverInfo.sourceDir)
          .append("!/lib/")
          .append(abi);
        final String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "gfx driver package libs: " + paths);
        setDriverPath(paths);
    }

    /**
     * Start a background thread to initialize EGL.
     *
     * Initializing EGL involves loading and initializing the graphics driver. Some drivers take
     * several 10s of milliseconds to do this, so doing it on-demand when an app tries to render
     * its first frame adds directly to user-visible app launch latency. By starting it earlier
     * on a separate thread, it can usually be finished well before the UI is ready to be drawn.
     *
     * Should only be called after chooseDriver().
     */
    public static void earlyInitEGL() {
        final Thread eglInitThread = new Thread(
                () -> {
                    EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                },
                "EGL Init");
        eglInitThread.start();
    }

    private static String chooseAbi(ApplicationInfo ai) {
        final String isa = VMRuntime.getCurrentInstructionSet();
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

    private static native void setLayerPaths(ClassLoader classLoader, String layerPaths);
    private static native void setDebugLayers(String layers);
    private static native void setDriverPath(String path);
}
