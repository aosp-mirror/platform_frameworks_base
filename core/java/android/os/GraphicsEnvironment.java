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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String SYSTEM_DRIVER_NAME = "system";
    private static final String SYSTEM_DRIVER_VERSION_NAME = "";
    private static final long SYSTEM_DRIVER_VERSION_CODE = 0;
    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";
    private static final String PROPERTY_GFX_DRIVER_BUILD_TIME = "ro.gfx.driver_build_time";
    private static final String METADATA_DRIVER_BUILD_TIME = "driver_build_time";
    private static final String ANGLE_RULES_FILE = "a4a_rules.json";
    private static final String ANGLE_TEMP_RULES = "debug.angle.rules";
    private static final String ACTION_ANGLE_FOR_ANDROID = "android.app.action.ANGLE_FOR_ANDROID";
    private static final String ACTION_ANGLE_FOR_ANDROID_TOAST_MESSAGE =
            "android.app.action.ANGLE_FOR_ANDROID_TOAST_MESSAGE";
    private static final String INTENT_KEY_A4A_TOAST_MESSAGE = "A4A Toast Message";
    private static final String GAME_DRIVER_WHITELIST_ALL = "*";

    private ClassLoader mClassLoader;
    private String mLayerPath;
    private String mDebugLayerPath;

    /**
     * Set up GraphicsEnvironment
     */
    public void setup(Context context, Bundle coreSettings) {
        final PackageManager pm = context.getPackageManager();
        final String packageName = context.getPackageName();
        Trace.traceBegin(Trace.TRACE_TAG_GRAPHICS, "setupGpuLayers");
        setupGpuLayers(context, coreSettings, pm, packageName);
        Trace.traceEnd(Trace.TRACE_TAG_GRAPHICS);
        Trace.traceBegin(Trace.TRACE_TAG_GRAPHICS, "setupAngle");
        setupAngle(context, coreSettings, pm, packageName);
        Trace.traceEnd(Trace.TRACE_TAG_GRAPHICS);
        Trace.traceBegin(Trace.TRACE_TAG_GRAPHICS, "chooseDriver");
        if (!chooseDriver(context, coreSettings, pm, packageName)) {
            setGpuStats(SYSTEM_DRIVER_NAME, SYSTEM_DRIVER_VERSION_NAME, SYSTEM_DRIVER_VERSION_CODE,
                    SystemProperties.getLong(PROPERTY_GFX_DRIVER_BUILD_TIME, 0), packageName);
        }
        Trace.traceEnd(Trace.TRACE_TAG_GRAPHICS);
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
    private static String getDebugLayerAppPaths(PackageManager pm, String app) {
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(app, PackageManager.MATCH_ALL);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Debug layer app '" + app + "' not installed");

            return null;
        }

        final String abi = chooseAbi(appInfo);

        final StringBuilder sb = new StringBuilder();
        sb.append(appInfo.nativeLibraryDir)
            .append(File.pathSeparator);
        sb.append(appInfo.sourceDir)
            .append("!/lib/")
            .append(abi);
        final String paths = sb.toString();

        if (DEBUG) Log.v(TAG, "Debug layer app libs: " + paths);

        return paths;
    }

    /**
     * Set up layer search paths for all apps
     * If debuggable, check for additional debug settings
     */
    private void setupGpuLayers(
            Context context, Bundle coreSettings, PackageManager pm, String packageName) {
        String layerPaths = "";

        // Only enable additional debug functionality if the following conditions are met:
        // 1. App is debuggable or device is rooted
        // 2. ENABLE_GPU_DEBUG_LAYERS is true
        // 3. Package name is equal to GPU_DEBUG_APP

        if (isDebuggable(context) || (getCanLoadSystemLibraries() == 1)) {

            final int enable = coreSettings.getInt(Settings.Global.ENABLE_GPU_DEBUG_LAYERS, 0);

            if (enable != 0) {

                final String gpuDebugApp = coreSettings.getString(Settings.Global.GPU_DEBUG_APP);

                if ((gpuDebugApp != null && packageName != null)
                        && (!gpuDebugApp.isEmpty() && !packageName.isEmpty())
                        && gpuDebugApp.equals(packageName)) {
                    Log.i(TAG, "GPU debug layers enabled for " + packageName);

                    // Prepend the debug layer path as a searchable path.
                    // This will ensure debug layers added will take precedence over
                    // the layers specified by the app.
                    layerPaths = mDebugLayerPath + ":";

                    // If there is a debug layer app specified, add its path.
                    final String gpuDebugLayerApp =
                            coreSettings.getString(Settings.Global.GPU_DEBUG_LAYER_APP);

                    if (gpuDebugLayerApp != null && !gpuDebugLayerApp.isEmpty()) {
                        Log.i(TAG, "GPU debug layer app: " + gpuDebugLayerApp);
                        final String paths = getDebugLayerAppPaths(pm, gpuDebugLayerApp);
                        if (paths != null) {
                            // Append the path so files placed in the app's base directory will
                            // override the external path
                            layerPaths += paths + ":";
                        }
                    }

                    final String layers = coreSettings.getString(Settings.Global.GPU_DEBUG_LAYERS);

                    Log.i(TAG, "Vulkan debug layer list: " + layers);
                    if (layers != null && !layers.isEmpty()) {
                        setDebugLayers(layers);
                    }

                    final String layersGLES =
                            coreSettings.getString(Settings.Global.GPU_DEBUG_LAYERS_GLES);

                    Log.i(TAG, "GLES debug layer list: " + layersGLES);
                    if (layersGLES != null && !layersGLES.isEmpty()) {
                        setDebugLayersGLES(layersGLES);
                    }
                }
            }
        }

        // Include the app's lib directory in all cases
        layerPaths += mLayerPath;

        setLayerPaths(mClassLoader, layerPaths);
    }

    enum OpenGlDriverChoice {
        DEFAULT,
        NATIVE,
        ANGLE
    }

    private static final Map<OpenGlDriverChoice, String> sDriverMap = buildMap();
    private static Map<OpenGlDriverChoice, String> buildMap() {
        final Map<OpenGlDriverChoice, String> map = new HashMap<>();
        map.put(OpenGlDriverChoice.DEFAULT, "default");
        map.put(OpenGlDriverChoice.ANGLE, "angle");
        map.put(OpenGlDriverChoice.NATIVE, "native");

        return map;
    }


    private static List<String> getGlobalSettingsString(ContentResolver contentResolver,
                                                        Bundle bundle,
                                                        String globalSetting) {
        final List<String> valueList;
        final String settingsValue;

        if (bundle != null) {
            settingsValue = bundle.getString(globalSetting);
        } else {
            settingsValue = Settings.Global.getString(contentResolver, globalSetting);
        }

        if (settingsValue != null) {
            valueList = new ArrayList<>(Arrays.asList(settingsValue.split(",")));
        } else {
            valueList = new ArrayList<>();
        }

        return valueList;
    }

    private static int getGlobalSettingsPkgIndex(String pkgName,
                                                 List<String> globalSettingsDriverPkgs) {
        for (int pkgIndex = 0; pkgIndex < globalSettingsDriverPkgs.size(); pkgIndex++) {
            if (globalSettingsDriverPkgs.get(pkgIndex).equals(pkgName)) {
                return pkgIndex;
            }
        }

        return -1;
    }

    private static String getDriverForPkg(Context context, Bundle bundle, String packageName) {
        final String allUseAngle;
        if (bundle != null) {
            allUseAngle =
                    bundle.getString(Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_ALL_ANGLE);
        } else {
            ContentResolver contentResolver = context.getContentResolver();
            allUseAngle = Settings.Global.getString(contentResolver,
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_ALL_ANGLE);
        }
        if ((allUseAngle != null) && allUseAngle.equals("1")) {
            return sDriverMap.get(OpenGlDriverChoice.ANGLE);
        }

        final ContentResolver contentResolver = context.getContentResolver();
        final List<String> globalSettingsDriverPkgs =
                getGlobalSettingsString(contentResolver, bundle,
                        Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_PKGS);
        final List<String> globalSettingsDriverValues =
                getGlobalSettingsString(contentResolver, bundle,
                        Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_VALUES);

        // Make sure we have a good package name
        if ((packageName == null) || (packageName.isEmpty())) {
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }
        // Make sure we have good settings to use
        if (globalSettingsDriverPkgs.size() != globalSettingsDriverValues.size()) {
            Log.w(TAG,
                    "Global.Settings values are invalid: "
                        + "globalSettingsDriverPkgs.size = "
                            + globalSettingsDriverPkgs.size() + ", "
                        + "globalSettingsDriverValues.size = "
                            + globalSettingsDriverValues.size());
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }

        final int pkgIndex = getGlobalSettingsPkgIndex(packageName, globalSettingsDriverPkgs);

        if (pkgIndex < 0) {
            return sDriverMap.get(OpenGlDriverChoice.DEFAULT);
        }

        return globalSettingsDriverValues.get(pkgIndex);
    }

    /**
     * Get the ANGLE package name.
     */
    private String getAnglePackageName(PackageManager pm) {
        final Intent intent = new Intent(ACTION_ANGLE_FOR_ANDROID);

        final List<ResolveInfo> resolveInfos =
                pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (resolveInfos.size() != 1) {
            Log.e(TAG, "Invalid number of ANGLE packages. Required: 1, Found: "
                    + resolveInfos.size());
            for (ResolveInfo resolveInfo : resolveInfos) {
                Log.e(TAG, "Found ANGLE package: " + resolveInfo.activityInfo.packageName);
            }
            return "";
        }

        // Must be exactly 1 ANGLE PKG found to get here.
        return resolveInfos.get(0).activityInfo.packageName;
    }

    /**
     * Attempt to setup ANGLE with a temporary rules file.
     * True: Temporary rules file was loaded.
     * False: Temporary rules file was *not* loaded.
     */
    private static boolean setupAngleWithTempRulesFile(Context context,
                                                String packageName,
                                                String paths,
                                                String devOptIn) {
        /**
         * We only want to load a temp rules file for:
         *  - apps that are marked 'debuggable' in their manifest
         *  - devices that are running a userdebug build (ro.debuggable) or can inject libraries for
         *    debugging (PR_SET_DUMPABLE).
         */
        final boolean appIsDebuggable = isDebuggable(context);
        final boolean deviceIsDebuggable = getCanLoadSystemLibraries() == 1;
        if (!(appIsDebuggable || deviceIsDebuggable)) {
            Log.v(TAG, "Skipping loading temporary rules file: "
                    + "appIsDebuggable = " + appIsDebuggable + ", "
                    + "adbRootEnabled = " + deviceIsDebuggable);
            return false;
        }

        final String angleTempRules = SystemProperties.get(ANGLE_TEMP_RULES);

        if ((angleTempRules == null) || angleTempRules.isEmpty()) {
            Log.v(TAG, "System property '" + ANGLE_TEMP_RULES + "' is not set or is empty");
            return false;
        }

        Log.i(TAG, "Detected system property " + ANGLE_TEMP_RULES + ": " + angleTempRules);

        final File tempRulesFile = new File(angleTempRules);
        if (tempRulesFile.exists()) {
            Log.i(TAG, angleTempRules + " exists, loading file.");
            try {
                final FileInputStream stream = new FileInputStream(angleTempRules);

                try {
                    final FileDescriptor rulesFd = stream.getFD();
                    final long rulesOffset = 0;
                    final long rulesLength = stream.getChannel().size();
                    Log.i(TAG, "Loaded temporary ANGLE rules from " + angleTempRules);

                    setAngleInfo(paths, packageName, devOptIn, rulesFd, rulesOffset, rulesLength);

                    stream.close();

                    // We successfully setup ANGLE, so return with good status
                    return true;
                } catch (IOException e) {
                    Log.w(TAG, "Hit IOException thrown by FileInputStream: " + e);
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Temp ANGLE rules file not found: " + e);
            } catch (SecurityException e) {
                Log.w(TAG, "Temp ANGLE rules file not accessible: " + e);
            }
        }

        return false;
    }

    /**
     * Attempt to setup ANGLE with a rules file loaded from the ANGLE APK.
     * True: APK rules file was loaded.
     * False: APK rules file was *not* loaded.
     */
    private static boolean setupAngleRulesApk(String anglePkgName,
            ApplicationInfo angleInfo,
            PackageManager pm,
            String packageName,
            String paths,
            String devOptIn) {
        // Pass the rules file to loader for ANGLE decisions
        try {
            final AssetManager angleAssets = pm.getResourcesForApplication(angleInfo).getAssets();

            try {
                final AssetFileDescriptor assetsFd = angleAssets.openFd(ANGLE_RULES_FILE);

                setAngleInfo(paths, packageName, devOptIn, assetsFd.getFileDescriptor(),
                        assetsFd.getStartOffset(), assetsFd.getLength());

                assetsFd.close();

                return true;
            } catch (IOException e) {
                Log.w(TAG, "Failed to get AssetFileDescriptor for " + ANGLE_RULES_FILE
                        + " from '" + anglePkgName + "': " + e);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get AssetManager for '" + anglePkgName + "': " + e);
        }

        return false;
    }

    /**
     * Pull ANGLE whitelist from GlobalSettings and compare against current package
     */
    private static boolean checkAngleWhitelist(Context context, Bundle bundle, String packageName) {
        final ContentResolver contentResolver = context.getContentResolver();
        final List<String> angleWhitelist =
                getGlobalSettingsString(contentResolver, bundle,
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_WHITELIST);

        return angleWhitelist.contains(packageName);
    }

    /**
     * Pass ANGLE details down to trigger enable logic
     *
     * @param context
     * @param bundle
     * @param packageName
     * @return true: ANGLE setup successfully
     *         false: ANGLE not setup (not on whitelist, ANGLE not present, etc.)
     */
    public boolean setupAngle(Context context, Bundle bundle, PackageManager pm,
            String packageName) {
        if (packageName.isEmpty()) {
            Log.v(TAG, "No package name available yet, skipping ANGLE setup");
            return false;
        }

        final String devOptIn = getDriverForPkg(context, bundle, packageName);
        if (DEBUG) {
            Log.v(TAG, "ANGLE Developer option for '" + packageName + "' "
                    + "set to: '" + devOptIn + "'");
        }

        // We only need to check rules if the app is whitelisted or the developer has
        // explicitly chosen something other than default driver.
        //
        // The whitelist will be generated by the ANGLE APK at both boot time and
        // ANGLE update time. It will only include apps mentioned in the rules file.
        //
        // If the user has set the developer option to something other than default,
        // we need to call setupAngleRulesApk() with the package name and the developer
        // option value (native/angle/other). Then later when we are actually trying to
        // load a driver, GraphicsEnv::shouldUseAngle() has seen the package name before
        // and can confidently answer yes/no based on the previously set developer
        // option value.
        final boolean whitelisted = checkAngleWhitelist(context, bundle, packageName);
        final boolean defaulted = devOptIn.equals(sDriverMap.get(OpenGlDriverChoice.DEFAULT));
        final boolean rulesCheck = (whitelisted || !defaulted);
        if (!rulesCheck) {
            return false;
        }

        if (whitelisted) {
            Log.v(TAG, "ANGLE whitelist includes " + packageName);
        }
        if (!defaulted) {
            Log.v(TAG, "ANGLE developer option for " + packageName + ": " + devOptIn);
        }

        final String anglePkgName = getAnglePackageName(pm);
        if (anglePkgName.isEmpty()) {
            Log.e(TAG, "Failed to find ANGLE package.");
            return false;
        }

        final ApplicationInfo angleInfo;
        try {
            angleInfo = pm.getApplicationInfo(anglePkgName, PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "ANGLE package '" + anglePkgName + "' not installed");
            return false;
        }

        final String abi = chooseAbi(angleInfo);

        // Build a path that includes installed native libs and APK
        final String paths = angleInfo.nativeLibraryDir
                + File.pathSeparator
                + angleInfo.sourceDir
                + "!/lib/"
                + abi;

        if (DEBUG) Log.v(TAG, "ANGLE package libs: " + paths);

        if (setupAngleWithTempRulesFile(context, packageName, paths, devOptIn)) {
            // We setup ANGLE with a temp rules file, so we're done here.
            return true;
        }

        if (setupAngleRulesApk(anglePkgName, angleInfo, pm, packageName, paths, devOptIn)) {
            // We setup ANGLE with rules from the APK, so we're done here.
            return true;
        }

        return false;
    }

    /**
     * Determine if the "ANGLE In Use" dialog box should be shown.
     */
    private boolean shouldShowAngleInUseDialogBox(Context context) {
        try {
            ContentResolver contentResolver = context.getContentResolver();
            final int showDialogBox = Settings.Global.getInt(contentResolver,
                    Settings.Global.GLOBAL_SETTINGS_SHOW_ANGLE_IN_USE_DIALOG_BOX);

            return (showDialogBox == 1);
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            // Do nothing and move on
        }

        // No setting, so assume false
        return false;
    }

    /**
     * Determine if ANGLE should be used.
     */
    private boolean shouldUseAngle(Context context, String packageName) {
        // Need to make sure we are evaluating ANGLE usage for the correct circumstances
        if (!setupAngle(context, null, context.getPackageManager(), packageName)) {
            Log.v(TAG, "Package '" + packageName + "' should use not ANGLE");
            return false;
        }

        final boolean useAngle = getShouldUseAngle(packageName);
        Log.v(TAG, "Package '" + packageName + "' should use ANGLE = '" + useAngle + "'");

        return useAngle;
    }

    /**
     * Show the ANGLE in Use Dialog Box
     * @param context
     */
    public void showAngleInUseDialogBox(Context context) {
        final String packageName = context.getPackageName();

        if (shouldShowAngleInUseDialogBox(context) && shouldUseAngle(context, packageName)) {
            final Intent intent = new Intent(ACTION_ANGLE_FOR_ANDROID_TOAST_MESSAGE);
            String anglePkg = getAnglePackageName(context.getPackageManager());
            intent.setPackage(anglePkg);

            context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Bundle results = getResultExtras(true);

                    String toastMsg = results.getString(INTENT_KEY_A4A_TOAST_MESSAGE);
                    final Toast toast = Toast.makeText(context, toastMsg, Toast.LENGTH_LONG);
                    toast.show();
                }
            }, null, Activity.RESULT_OK, null, null);
        }
    }

    /**
     * Choose whether the current process should use the builtin or an updated driver.
     */
    private static boolean chooseDriver(
            Context context, Bundle coreSettings, PackageManager pm, String packageName) {
        final String driverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        if (driverPackageName == null || driverPackageName.isEmpty()) {
            return false;
        }

        final PackageInfo driverPackageInfo;
        try {
            driverPackageInfo = pm.getPackageInfo(driverPackageName,
                    PackageManager.MATCH_SYSTEM_ONLY | PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "driver package '" + driverPackageName + "' not installed");
            return false;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        final ApplicationInfo driverAppInfo = driverPackageInfo.applicationInfo;
        if (driverAppInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Log.w(TAG, "updated driver package is not known to be compatible with O");
            }
            return false;
        }

        // To minimize risk of driver updates crippling the device beyond user repair, never use an
        // updated driver for privileged or non-updated system apps. Presumably pre-installed apps
        // were tested thoroughly with the pre-installed driver.
        final ApplicationInfo ai = context.getApplicationInfo();
        if (ai.isPrivilegedApp() || (ai.isSystemApp() && !ai.isUpdatedSystemApp())) {
            if (DEBUG) Log.v(TAG, "ignoring driver package for privileged/non-updated system app");
            return false;
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
            return false;
        }

        if (gameDriverAllApps != 1) {
            // GAME_DRIVER_OPT_OUT_APPS has higher priority than GAME_DRIVER_OPT_IN_APPS
            if (getGlobalSettingsString(null, coreSettings,
                    Settings.Global.GAME_DRIVER_OPT_OUT_APPS).contains(packageName)) {
                if (DEBUG) {
                    Log.w(TAG, packageName + " opts out from Game Driver.");
                }
                return false;
            }
            final boolean isOptIn =
                    getGlobalSettingsString(null, coreSettings,
                            Settings.Global.GAME_DRIVER_OPT_IN_APPS).contains(packageName);
            final List<String> whitelist = getGlobalSettingsString(null, coreSettings,
                    Settings.Global.GAME_DRIVER_WHITELIST);
            if (!isOptIn && whitelist.indexOf(GAME_DRIVER_WHITELIST_ALL) != 0
                    && !whitelist.contains(packageName)) {
                if (DEBUG) {
                    Log.w(TAG, packageName + " is not on the whitelist.");
                }
                return false;
            }

            // If the application is not opted-in and check whether it's on the blacklist,
            // terminate early if it's on the blacklist and fallback to system driver.
            if (!isOptIn
                    && getGlobalSettingsString(null, coreSettings,
                                               Settings.Global.GAME_DRIVER_BLACKLIST)
                            .contains(ai.packageName)) {
                return false;
            }
        }

        final String abi = chooseAbi(driverAppInfo);
        if (abi == null) {
            if (DEBUG) {
                // This is the normal case for the pre-installed empty driver package, don't spam
                if (driverAppInfo.isUpdatedSystemApp()) {
                    Log.w(TAG, "updated driver package has no compatible native libraries");
                }
            }
            return false;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(driverAppInfo.nativeLibraryDir)
          .append(File.pathSeparator);
        sb.append(driverAppInfo.sourceDir)
          .append("!/lib/")
          .append(abi);
        final String paths = sb.toString();

        final String sphalLibraries =
                coreSettings.getString(Settings.Global.GAME_DRIVER_SPHAL_LIBRARIES);

        if (DEBUG) {
            Log.v(TAG,
                    "gfx driver package search path: " + paths
                            + ", required sphal libraries: " + sphalLibraries);
        }
        setDriverPathAndSphalLibraries(paths, sphalLibraries);

        if (driverAppInfo.metaData == null) {
            throw new NullPointerException("apk's meta-data cannot be null");
        }

        final String driverBuildTime = driverAppInfo.metaData.getString(METADATA_DRIVER_BUILD_TIME);
        if (driverBuildTime == null || driverBuildTime.isEmpty()) {
            throw new IllegalArgumentException("driver_build_time meta-data is not set");
        }
        // driver_build_time in the meta-data is in "L<Unix epoch timestamp>" format. e.g. L123456.
        // Long.parseLong will throw if the meta-data "driver_build_time" is not set properly.
        setGpuStats(driverPackageName, driverPackageInfo.versionName, driverAppInfo.longVersionCode,
                Long.parseLong(driverBuildTime.substring(1)), packageName);

        return true;
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

    private static native int getCanLoadSystemLibraries();
    private static native void setLayerPaths(ClassLoader classLoader, String layerPaths);
    private static native void setDebugLayers(String layers);
    private static native void setDebugLayersGLES(String layers);
    private static native void setDriverPathAndSphalLibraries(String path, String sphalLibraries);
    private static native void setGpuStats(String driverPackageName, String driverVersionName,
            long driverVersionCode, long driverBuildTime, String appPackageName);
    private static native void setAngleInfo(String path, String appPackage, String devOptIn,
            FileDescriptor rulesFd, long rulesOffset, long rulesLength);
    private static native boolean getShouldUseAngle(String packageName);
}
