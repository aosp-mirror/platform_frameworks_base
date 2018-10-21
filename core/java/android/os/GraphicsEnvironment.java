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
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import dalvik.system.VMRuntime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

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
    private static final String PROPERTY_GFX_DRIVER_WHITELIST = "ro.gfx.driver.whitelist.0";
    private static final String ANGLE_PACKAGE_NAME = "com.android.angle";
    private static final String GLES_MODE_METADATA_KEY = "com.android.angle.GLES_MODE";
    private static final String ANGLE_RULES_FILE = "a4a_rules.json";

    private ClassLoader mClassLoader;
    private String mLayerPath;
    private String mDebugLayerPath;

    /**
     * Set up GraphicsEnvironment
     */
    public void setup(Context context, Bundle coreSettings) {
        setupGpuLayers(context, coreSettings);
        setupAngle(context, coreSettings);
        chooseDriver(context);
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
     * Return the debug layer app's on-disk and in-APK lib directories
     */
    private static String getDebugLayerAppPaths(Context context, String app) {
        ApplicationInfo appInfo;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(
                    app, PackageManager.MATCH_ALL);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Debug layer app '" + app + "' not installed");

            return null;
        }

        String abi = chooseAbi(appInfo);

        StringBuilder sb = new StringBuilder();
        sb.append(appInfo.nativeLibraryDir)
            .append(File.pathSeparator);
        sb.append(appInfo.sourceDir)
            .append("!/lib/")
            .append(abi);
        String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "Debug layer app libs: " + paths);

        return paths;
    }

    /**
     * Set up layer search paths for all apps
     * If debuggable, check for additional debug settings
     */
    private void setupGpuLayers(Context context, Bundle coreSettings) {

        String layerPaths = "";

        // Only enable additional debug functionality if the following conditions are met:
        // 1. App is debuggable or device is rooted
        // 2. ENABLE_GPU_DEBUG_LAYERS is true
        // 3. Package name is equal to GPU_DEBUG_APP

        if (isDebuggable(context) || (getCanLoadSystemLibraries() == 1)) {

            int enable = coreSettings.getInt(Settings.Global.ENABLE_GPU_DEBUG_LAYERS, 0);

            if (enable != 0) {

                String gpuDebugApp = coreSettings.getString(Settings.Global.GPU_DEBUG_APP);

                String packageName = context.getPackageName();

                if ((gpuDebugApp != null && packageName != null)
                        && (!gpuDebugApp.isEmpty() && !packageName.isEmpty())
                        && gpuDebugApp.equals(packageName)) {
                    Log.i(TAG, "GPU debug layers enabled for " + packageName);

                    // Prepend the debug layer path as a searchable path.
                    // This will ensure debug layers added will take precedence over
                    // the layers specified by the app.
                    layerPaths = mDebugLayerPath + ":";


                    // If there is a debug layer app specified, add its path.
                    String gpuDebugLayerApp =
                            coreSettings.getString(Settings.Global.GPU_DEBUG_LAYER_APP);

                    if (gpuDebugLayerApp != null && !gpuDebugLayerApp.isEmpty()) {
                        Log.i(TAG, "GPU debug layer app: " + gpuDebugLayerApp);
                        String paths = getDebugLayerAppPaths(context, gpuDebugLayerApp);
                        if (paths != null) {
                            // Append the path so files placed in the app's base directory will
                            // override the external path
                            layerPaths += paths + ":";
                        }
                    }

                    String layers = coreSettings.getString(Settings.Global.GPU_DEBUG_LAYERS);

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

    /**
     * Pass ANGLE details down to trigger enable logic
     */
    private static void setupAngle(Context context, Bundle coreSettings) {

        String angleEnabledApp =
                coreSettings.getString(Settings.Global.ANGLE_ENABLED_APP);

        String packageName = context.getPackageName();

        boolean devOptIn = false;
        if ((angleEnabledApp != null && packageName != null)
                && (!angleEnabledApp.isEmpty() && !packageName.isEmpty())
                && angleEnabledApp.equals(packageName)) {

            if (DEBUG) Log.v(TAG, packageName + " opted in for ANGLE via Developer Setting");

            devOptIn = true;
        }

        ApplicationInfo appInfo;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(packageName,
                PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get info about current application: " + packageName);
            return;
        }

        String appPref = "dontcare";
        final BaseBundle metadata = appInfo.metaData;
        if (metadata != null) {
            final String glesMode = metadata.getString(GLES_MODE_METADATA_KEY);
            if (glesMode != null) {
                if (glesMode.equals("angle")) {
                    appPref = "angle";
                    if (DEBUG) Log.v(TAG, packageName + " opted for ANGLE via AndroidManifest");
                } else if (glesMode.equals("native")) {
                    appPref = "native";
                    if (DEBUG) Log.v(TAG, packageName + " opted for NATIVE via AndroidManifest");
                } else {
                    Log.w(TAG, "Unrecognized GLES_MODE (\"" + glesMode + "\") for " + packageName
                               + ". Supported values are \"angle\" or \"native\"");
                }
            }
        }

        ApplicationInfo angleInfo;
        try {
            angleInfo = context.getPackageManager().getApplicationInfo(ANGLE_PACKAGE_NAME,
                PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "ANGLE package '" + ANGLE_PACKAGE_NAME + "' not installed");
            return;
        }

        String abi = chooseAbi(angleInfo);

        // Build a path that includes installed native libs and APK
        StringBuilder sb = new StringBuilder();
        sb.append(angleInfo.nativeLibraryDir)
            .append(File.pathSeparator)
            .append(angleInfo.sourceDir)
            .append("!/lib/")
            .append(abi);
        String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "ANGLE package libs: " + paths);

        // Pass the rules file to loader for ANGLE decisions
        AssetManager angleAssets = null;
        try {
            angleAssets =
                context.getPackageManager().getResourcesForApplication(angleInfo).getAssets();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get AssetManager for '" + ANGLE_PACKAGE_NAME + "'");
            return;
        }

        AssetFileDescriptor assetsFd = null;
        try {
            assetsFd = angleAssets.openFd(ANGLE_RULES_FILE);
        } catch (IOException e) {
            Log.w(TAG, "Failed to get AssetFileDescriptor for " + ANGLE_RULES_FILE + " from "
                       + "'" + ANGLE_PACKAGE_NAME + "'");
            return;
        }

        FileDescriptor rulesFd = null;
        long rulesOffset = 0;
        long rulesLength = 0;
        if (assetsFd != null) {
            rulesFd = assetsFd.getFileDescriptor();
            rulesOffset = assetsFd.getStartOffset();
            rulesLength = assetsFd.getLength();
        } else {
            Log.w(TAG, "Failed to get file descriptor for " + ANGLE_RULES_FILE);
            return;
        }

        // Further opt-in logic is handled in native, so pass relevant info down
        setAngleInfo(paths, packageName, appPref, devOptIn,
                     rulesFd, rulesOffset, rulesLength);
    }

    /**
     * Choose whether the current process should use the builtin or an updated driver.
     */
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
        Set<String> whitelist = loadWhitelist(context, driverPackageName);

        // Empty whitelist implies no updatable graphics driver. Typically, the pre-installed
        // updatable graphics driver is supposed to be a place holder and contains no graphics
        // driver and whitelist.
        if (whitelist == null || whitelist.isEmpty()) {
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
        if (!whitelist.contains(context.getPackageName())) {
            if (DEBUG) {
                Log.w(TAG, context.getPackageName() + " is not on the whitelist.");
            }
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
        Thread eglInitThread = new Thread(
                () -> {
                    EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                },
                "EGL Init");
        eglInitThread.start();
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

    private static Set<String> loadWhitelist(Context context, String driverPackageName) {
        String whitelistName = SystemProperties.get(PROPERTY_GFX_DRIVER_WHITELIST);
        if (whitelistName == null || whitelistName.isEmpty()) {
            return null;
        }
        try {
            Context driverContext = context.createPackageContext(driverPackageName,
                                                                 Context.CONTEXT_RESTRICTED);
            AssetManager assets = driverContext.getAssets();
            InputStream stream = assets.open(whitelistName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            Set<String> whitelist = new HashSet<>();
            for (String line; (line = reader.readLine()) != null; ) {
                whitelist.add(line);
            }
            return whitelist;
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            }
        } catch (IOException e) {
            if (DEBUG) {
                Log.w(TAG, "Failed to load whitelist driver package, abort.");
            }
        }
        return null;
    }

    private static native int getCanLoadSystemLibraries();
    private static native void setLayerPaths(ClassLoader classLoader, String layerPaths);
    private static native void setDebugLayers(String layers);
    private static native void setDriverPath(String path);
    private static native void setAngleInfo(String path, String appPackage, String appPref,
                                            boolean devOptIn, FileDescriptor rulesFd,
                                            long rulesOffset, long rulesLength);
}
