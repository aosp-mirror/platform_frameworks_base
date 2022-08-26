/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.EXTRA_REPLACING;

import static com.android.internal.R.styleable.GameModeConfig_allowGameAngleDriver;
import static com.android.internal.R.styleable.GameModeConfig_allowGameDownscaling;
import static com.android.internal.R.styleable.GameModeConfig_allowGameFpsOverride;
import static com.android.internal.R.styleable.GameModeConfig_supportsBatteryGameMode;
import static com.android.internal.R.styleable.GameModeConfig_supportsPerformanceGameMode;
import static com.android.server.wm.CompatModePackages.DOWNSCALED;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_30;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_35;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_40;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_45;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_50;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_55;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_60;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_65;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_70;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_75;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_80;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_85;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_90;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameManager.GameMode;
import android.app.GameModeInfo;
import android.app.GameState;
import android.app.IGameManagerService;
import android.app.compat.PackageOverride;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Service to manage game related features.
 *
 * <p>Game service is a core service that monitors, coordinates game related features,
 * as well as collect metrics.</p>
 *
 * @hide
 */
public final class GameManagerService extends IGameManagerService.Stub {
    public static final String TAG = "GameManagerService";

    private static final boolean DEBUG = false;

    static final int WRITE_SETTINGS = 1;
    static final int REMOVE_SETTINGS = 2;
    static final int POPULATE_GAME_MODE_SETTINGS = 3;
    static final int SET_GAME_STATE = 4;
    static final int CANCEL_GAME_LOADING_MODE = 5;
    static final int WRITE_GAME_MODE_INTERVENTION_LIST_FILE = 6;
    static final int WRITE_SETTINGS_DELAY = 10 * 1000;  // 10 seconds
    static final int LOADING_BOOST_MAX_DURATION = 5 * 1000;  // 5 seconds

    static final PackageOverride COMPAT_ENABLED = new PackageOverride.Builder().setEnabled(true)
            .build();
    static final PackageOverride COMPAT_DISABLED = new PackageOverride.Builder().setEnabled(false)
            .build();
    private static final String PACKAGE_NAME_MSG_KEY = "packageName";
    private static final String USER_ID_MSG_KEY = "userId";
    private static final String GAME_MODE_INTERVENTION_LIST_FILE_NAME =
            "game_mode_intervention.list";

    private final Context mContext;
    private final Object mLock = new Object();
    private final Object mDeviceConfigLock = new Object();
    private final Object mOverrideConfigLock = new Object();
    private final Handler mHandler;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final IPlatformCompat mPlatformCompat;
    private final PowerManagerInternal mPowerManagerInternal;
    private final File mSystemDir;
    @VisibleForTesting
    final AtomicFile mGameModeInterventionListFile;
    private DeviceConfigListener mDeviceConfigListener;
    @GuardedBy("mLock")
    private final ArrayMap<Integer, GameManagerSettings> mSettings = new ArrayMap<>();
    @GuardedBy("mDeviceConfigLock")
    private final ArrayMap<String, GamePackageConfiguration> mConfigs = new ArrayMap<>();
    @GuardedBy("mOverrideConfigLock")
    private final ArrayMap<String, GamePackageConfiguration> mOverrideConfigs = new ArrayMap<>();
    @Nullable
    private final GameServiceController mGameServiceController;

    public GameManagerService(Context context) {
        this(context, createServiceThread().getLooper());
    }

    GameManagerService(Context context, Looper looper) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mSystemDir = new File(Environment.getDataDirectory(), "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mGameModeInterventionListFile = new AtomicFile(new File(mSystemDir,
                                                     GAME_MODE_INTERVENTION_LIST_FILE_NAME));
        FileUtils.setPermissions(mGameModeInterventionListFile.getBaseFile().getAbsolutePath(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR
                        | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                -1, -1);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_GAME_SERVICE)) {
            mGameServiceController = new GameServiceController(
                    context, BackgroundThread.getExecutor(),
                    new GameServiceProviderSelectorImpl(
                            context.getResources(),
                            context.getPackageManager()),
                    new GameServiceProviderInstanceFactoryImpl(context));
        } else {
            mGameServiceController = null;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    GameManagerService(Context context, Looper looper, File dataDir) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mGameModeInterventionListFile = new AtomicFile(new File(mSystemDir,
                GAME_MODE_INTERVENTION_LIST_FILE_NAME));
        FileUtils.setPermissions(mGameModeInterventionListFile.getBaseFile().getAbsolutePath(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR
                        | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                -1, -1);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_GAME_SERVICE)) {
            mGameServiceController = new GameServiceController(
                    context, BackgroundThread.getExecutor(),
                    new GameServiceProviderSelectorImpl(
                            context.getResources(),
                            context.getPackageManager()),
                    new GameServiceProviderInstanceFactoryImpl(context));
        } else {
            mGameServiceController = null;
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new GameManagerShellCommand().exec(this, in, out, err, args, callback, result);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            writer.println("Permission Denial: can't dump GameManagerService from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }
        if (args == null || args.length == 0) {
            writer.println("*Dump GameManagerService*");
            dumpAllGameConfigs(writer);
        }
    }

    private void dumpAllGameConfigs(PrintWriter pw) {
        final int userId = ActivityManager.getCurrentUser();
        String[] packageList = getInstalledGamePackageNames(userId);
        for (final String packageName : packageList) {
            pw.println(getInterventionList(packageName));
        }
    }

    class SettingsHandler extends Handler {

        SettingsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            doHandleMessage(msg);
        }

        void doHandleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeMessages(WRITE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mLock) {
                        removeMessages(WRITE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            GameManagerSettings userSettings = mSettings.get(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case REMOVE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeMessages(WRITE_SETTINGS, msg.obj);
                            removeMessages(REMOVE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    synchronized (mLock) {
                        // Since the user was removed, ignore previous write message
                        // and do write here.
                        removeMessages(WRITE_SETTINGS, msg.obj);
                        removeMessages(REMOVE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            final GameManagerSettings userSettings = mSettings.get(userId);
                            mSettings.remove(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    break;
                }
                case POPULATE_GAME_MODE_SETTINGS: {
                    // Scan all game packages and re-enforce the configured compat mode overrides
                    // as the DeviceConfig may have be wiped/since last reboot and we can't risk
                    // having overrides configured for packages that no longer have any DeviceConfig
                    // and thus any way to escape compat mode.
                    removeMessages(POPULATE_GAME_MODE_SETTINGS, msg.obj);
                    final int userId = (int) msg.obj;
                    final String[] packageNames = getInstalledGamePackageNames(userId);
                    updateConfigsForUser(userId, packageNames);
                    break;
                }
                case SET_GAME_STATE: {
                    final GameState gameState = (GameState) msg.obj;
                    final boolean isLoading = gameState.isLoading();
                    final Bundle data = msg.getData();
                    final String packageName = data.getString(PACKAGE_NAME_MSG_KEY);
                    final int userId = data.getInt(USER_ID_MSG_KEY);

                    // Restrict to games only. Requires performance mode to be enabled.
                    final boolean boostEnabled =
                            getGameMode(packageName, userId) == GameManager.GAME_MODE_PERFORMANCE;
                    int uid;
                    try {
                        uid = mPackageManager.getPackageUidAsUser(packageName, userId);
                    } catch (NameNotFoundException e) {
                        Slog.v(TAG, "Failed to get package metadata");
                        uid = -1;
                    }
                    FrameworkStatsLog.write(FrameworkStatsLog.GAME_STATE_CHANGED, packageName, uid,
                            boostEnabled, gameStateModeToStatsdGameState(gameState.getMode()),
                            isLoading, gameState.getLabel(), gameState.getQuality());

                    if (boostEnabled) {
                        if (mPowerManagerInternal == null) {
                            Slog.d(TAG, "Error setting loading mode for package " + packageName
                                    + " and userId " + userId);
                            break;
                        }
                        mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, isLoading);
                    }
                    break;
                }
                case CANCEL_GAME_LOADING_MODE: {
                    mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, false);
                    break;
                }
                case WRITE_GAME_MODE_INTERVENTION_LIST_FILE: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write setting for invalid user: " + userId);
                        synchronized (mLock) {
                            removeMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, null);
                        }
                        break;
                    }

                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    removeMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, null);
                    writeGameModeInterventionsToFile(userId);
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
            }
        }
    }

    private class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {

        DeviceConfigListener() {
            super();
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_GAME_OVERLAY,
                    mContext.getMainExecutor(), this);
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            final String[] packageNames = properties.getKeyset().toArray(new String[0]);
            updateConfigsForUser(ActivityManager.getCurrentUser(), packageNames);
        }

        @Override
        public void finalize() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }
    }

    // Turn the raw string to the corresponding CompatChange id.
    static long getCompatChangeId(String raw) {
        switch (raw) {
            case "0.3":
                return DOWNSCALE_30;
            case "0.35":
                return DOWNSCALE_35;
            case "0.4":
                return DOWNSCALE_40;
            case "0.45":
                return DOWNSCALE_45;
            case "0.5":
                return DOWNSCALE_50;
            case "0.55":
                return DOWNSCALE_55;
            case "0.6":
                return DOWNSCALE_60;
            case "0.65":
                return DOWNSCALE_65;
            case "0.7":
                return DOWNSCALE_70;
            case "0.75":
                return DOWNSCALE_75;
            case "0.8":
                return DOWNSCALE_80;
            case "0.85":
                return DOWNSCALE_85;
            case "0.9":
                return DOWNSCALE_90;
        }
        return 0;
    }

    public enum FrameRate {
        FPS_DEFAULT(0),
        FPS_30(30),
        FPS_40(40),
        FPS_45(45),
        FPS_60(60),
        FPS_90(90),
        FPS_120(120),
        FPS_INVALID(-1);

        public final int fps;

        FrameRate(int fps) {
            this.fps = fps;
        }
    }

    // Turn the raw string to the corresponding fps int.
    // Return 0 when disabling, -1 for invalid fps.
    static int getFpsInt(String raw) {
        switch (raw) {
            case "30":
                return FrameRate.FPS_30.fps;
            case "40":
                return FrameRate.FPS_40.fps;
            case "45":
                return FrameRate.FPS_45.fps;
            case "60":
                return FrameRate.FPS_60.fps;
            case "90":
                return FrameRate.FPS_90.fps;
            case "120":
                return FrameRate.FPS_120.fps;
            case "disable":
            case "":
                return FrameRate.FPS_DEFAULT.fps;
        }
        return FrameRate.FPS_INVALID.fps;
    }

    /**
     * Called by games to communicate the current state to the platform.
     *
     * @param packageName The client package name.
     * @param gameState   An object set to the current state.
     * @param userId      The user associated with this state.
     */
    public void setGameState(String packageName, @NonNull GameState gameState,
            @UserIdInt int userId) {
        if (!isPackageGame(packageName, userId)) {
            // Restrict to games only.
            return;
        }
        final Message msg = mHandler.obtainMessage(SET_GAME_STATE);
        final Bundle data = new Bundle();
        data.putString(PACKAGE_NAME_MSG_KEY, packageName);
        data.putInt(USER_ID_MSG_KEY, userId);
        msg.setData(data);
        msg.obj = gameState;
        mHandler.sendMessage(msg);
    }

    /**
     * GamePackageConfiguration manages all game mode config details for its associated package.
     */
    @VisibleForTesting
    public class GamePackageConfiguration {
        public static final String TAG = "GameManagerService_GamePackageConfiguration";

        /**
         * Metadata that can be included in the app manifest to allow/disallow any window manager
         * downscaling interventions. Default value is TRUE.
         */
        public static final String METADATA_WM_ALLOW_DOWNSCALE =
                "com.android.graphics.intervention.wm.allowDownscale";

        /**
         * Metadata that can be included in the app manifest to allow/disallow any ANGLE
         * interventions. Default value is TRUE.
         */
        public static final String METADATA_ANGLE_ALLOW_ANGLE =
                "com.android.graphics.intervention.angle.allowAngle";

        /**
         * Metadata that needs to be included in the app manifest to OPT-IN to PERFORMANCE mode.
         * This means the app will assume full responsibility for the experience provided by this
         * mode and the system will enable no window manager downscaling.
         * Default value is FALSE
         */
        public static final String METADATA_PERFORMANCE_MODE_ENABLE =
                "com.android.app.gamemode.performance.enabled";

        /**
         * Metadata that needs to be included in the app manifest to OPT-IN to BATTERY mode.
         * This means the app will assume full responsibility for the experience provided by this
         * mode and the system will enable no window manager downscaling.
         * Default value is FALSE
         */
        public static final String METADATA_BATTERY_MODE_ENABLE =
                "com.android.app.gamemode.battery.enabled";

        /**
         * Metadata that allows a game to specify all intervention information with an XML file in
         * the application field.
         */
        public static final String METADATA_GAME_MODE_CONFIG = "android.game_mode_config";

        private static final String GAME_MODE_CONFIG_NODE_NAME = "game-mode-config";
        private final String mPackageName;
        private final ArrayMap<Integer, GameModeConfiguration> mModeConfigs;
        private boolean mPerfModeOptedIn = false;
        private boolean mBatteryModeOptedIn = false;
        private boolean mAllowDownscale = true;
        private boolean mAllowAngle = true;
        private boolean mAllowFpsOverride = true;

        GamePackageConfiguration(String packageName, int userId) {
            mPackageName = packageName;
            mModeConfigs = new ArrayMap<>();

            try {
                final ApplicationInfo ai = mPackageManager.getApplicationInfoAsUser(packageName,
                        PackageManager.GET_META_DATA, userId);
                if (!parseInterventionFromXml(ai, packageName) && ai.metaData != null) {
                    mPerfModeOptedIn = ai.metaData.getBoolean(METADATA_PERFORMANCE_MODE_ENABLE);
                    mBatteryModeOptedIn = ai.metaData.getBoolean(METADATA_BATTERY_MODE_ENABLE);
                    mAllowDownscale = ai.metaData.getBoolean(METADATA_WM_ALLOW_DOWNSCALE, true);
                    mAllowAngle = ai.metaData.getBoolean(METADATA_ANGLE_ALLOW_ANGLE, true);
                }
            } catch (NameNotFoundException e) {
                // Not all packages are installed, hence ignore those that are not installed yet.
                Slog.v(TAG, "Failed to get package metadata");
            }
            final String configString = DeviceConfig.getProperty(
                    DeviceConfig.NAMESPACE_GAME_OVERLAY, packageName);
            if (configString != null) {
                final String[] gameModeConfigStrings = configString.split(":");
                for (String gameModeConfigString : gameModeConfigStrings) {
                    try {
                        final KeyValueListParser parser = new KeyValueListParser(',');
                        parser.setString(gameModeConfigString);
                        addModeConfig(new GameModeConfiguration(parser));
                    } catch (IllegalArgumentException e) {
                        Slog.e(TAG, "Invalid config string");
                    }
                }
            }
        }

        private boolean parseInterventionFromXml(ApplicationInfo ai, String packageName) {
            boolean xmlFound = false;
            try (XmlResourceParser parser = ai.loadXmlMetaData(mPackageManager,
                    METADATA_GAME_MODE_CONFIG)) {
                if (parser == null) {
                    Slog.v(TAG, "No " + METADATA_GAME_MODE_CONFIG
                            + " meta-data found for package " + mPackageName);
                } else {
                    xmlFound = true;
                    final Resources resources = mPackageManager.getResourcesForApplication(
                            packageName);
                    final AttributeSet attributeSet = Xml.asAttributeSet(parser);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && type != XmlPullParser.START_TAG) {
                        // Do nothing
                    }

                    boolean isStartingTagGameModeConfig =
                            GAME_MODE_CONFIG_NODE_NAME.equals(parser.getName());
                    if (!isStartingTagGameModeConfig) {
                        Slog.w(TAG, "Meta-data does not start with "
                                + GAME_MODE_CONFIG_NODE_NAME
                                + " tag");
                    } else {
                        final TypedArray array = resources.obtainAttributes(attributeSet,
                                com.android.internal.R.styleable.GameModeConfig);
                        mPerfModeOptedIn = array.getBoolean(
                                GameModeConfig_supportsPerformanceGameMode, false);
                        mBatteryModeOptedIn = array.getBoolean(
                                GameModeConfig_supportsBatteryGameMode,
                                false);
                        mAllowDownscale = array.getBoolean(GameModeConfig_allowGameDownscaling,
                                true);
                        mAllowAngle = array.getBoolean(GameModeConfig_allowGameAngleDriver, true);
                        mAllowFpsOverride = array.getBoolean(GameModeConfig_allowGameFpsOverride,
                                true);
                        array.recycle();
                    }
                }
            } catch (NameNotFoundException | XmlPullParserException | IOException ex) {
                // set flag back to default values when parsing fails
                mPerfModeOptedIn = false;
                mBatteryModeOptedIn = false;
                mAllowDownscale = true;
                mAllowAngle = true;
                mAllowFpsOverride = true;
                Slog.e(TAG, "Error while parsing XML meta-data for "
                        + METADATA_GAME_MODE_CONFIG);
            }
            return xmlFound;
        }

        /**
         * GameModeConfiguration contains all the values for all the interventions associated with
         * a game mode.
         */
        @VisibleForTesting
        public class GameModeConfiguration {
            public static final String TAG = "GameManagerService_GameModeConfiguration";
            public static final String MODE_KEY = "mode";
            public static final String SCALING_KEY = "downscaleFactor";
            public static final String FPS_KEY = "fps";
            public static final String DEFAULT_SCALING = "1.0";
            public static final String DEFAULT_FPS = "";
            public static final String ANGLE_KEY = "useAngle";
            public static final String LOADING_BOOST_KEY = "loadingBoost";

            private final @GameMode int mGameMode;
            private String mScaling;
            private String mFps;
            private final boolean mUseAngle;
            private final int mLoadingBoostDuration;

            GameModeConfiguration(KeyValueListParser parser) {
                mGameMode = parser.getInt(MODE_KEY, GameManager.GAME_MODE_UNSUPPORTED);
                // isGameModeOptedIn() returns if an app will handle all of the changes necessary
                // for a particular game mode. If so, the Android framework (i.e.
                // GameManagerService) will not do anything for the app (like window scaling or
                // using ANGLE).
                mScaling = !mAllowDownscale || willGamePerformOptimizations(mGameMode)
                        ? DEFAULT_SCALING : parser.getString(SCALING_KEY, DEFAULT_SCALING);

                mFps = mAllowFpsOverride && !willGamePerformOptimizations(mGameMode)
                        ? parser.getString(FPS_KEY, DEFAULT_FPS) : DEFAULT_FPS;
                // We only want to use ANGLE if:
                // - We're allowed to use ANGLE (the app hasn't opted out via the manifest) AND
                // - The app has not opted in to performing the work itself AND
                // - The Phenotype config has enabled it.
                mUseAngle = mAllowAngle && !willGamePerformOptimizations(mGameMode)
                        && parser.getBoolean(ANGLE_KEY, false);

                mLoadingBoostDuration = willGamePerformOptimizations(mGameMode) ? -1
                        : parser.getInt(LOADING_BOOST_KEY, -1);
            }

            public int getGameMode() {
                return mGameMode;
            }

            public String getScaling() {
                return mScaling;
            }

            public int getFps() {
                return GameManagerService.getFpsInt(mFps);
            }

            public boolean getUseAngle() {
                return mUseAngle;
            }

            public int getLoadingBoostDuration() {
                return mLoadingBoostDuration;
            }

            public void setScaling(String scaling) {
                mScaling = scaling;
            }

            public void setFpsStr(String fpsStr) {
                mFps = fpsStr;
            }

            public boolean isValid() {
                return (mGameMode == GameManager.GAME_MODE_STANDARD
                        || mGameMode == GameManager.GAME_MODE_PERFORMANCE
                        || mGameMode == GameManager.GAME_MODE_BATTERY)
                        && !willGamePerformOptimizations(mGameMode);
            }

            /**
             * @hide
             */
            public String toString() {
                return "[Game Mode:" + mGameMode + ",Scaling:" + mScaling + ",Use Angle:"
                        + mUseAngle + ",Fps:" + mFps + ",Loading Boost Duration:"
                        + mLoadingBoostDuration + "]";
            }

            /**
             * Get the corresponding compat change id for the current scaling string.
             */
            public long getCompatChangeId() {
                return GameManagerService.getCompatChangeId(mScaling);
            }
        }

        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Returns if the app will assume full responsibility for the experience provided by this
         * mode. If True, the system will not perform any interventions for the app.
         *
         * @return True if the app package has specified in its metadata either:
         * "com.android.app.gamemode.performance.enabled" or
         * "com.android.app.gamemode.battery.enabled" with a value of "true"
         */
        public boolean willGamePerformOptimizations(@GameMode int gameMode) {
            return (mBatteryModeOptedIn && gameMode == GameManager.GAME_MODE_BATTERY)
                    || (mPerfModeOptedIn && gameMode == GameManager.GAME_MODE_PERFORMANCE);
        }

        private int getAvailableGameModesBitfield() {
            int field = 0;
            for (final int mode : mModeConfigs.keySet()) {
                field |= modeToBitmask(mode);
            }
            if (mBatteryModeOptedIn) {
                field |= modeToBitmask(GameManager.GAME_MODE_BATTERY);
            }
            if (mPerfModeOptedIn) {
                field |= modeToBitmask(GameManager.GAME_MODE_PERFORMANCE);
            }
            // The lowest bit is reserved for UNSUPPORTED, STANDARD is supported if we support any
            // other mode.
            if (field > 1) {
                field |= modeToBitmask(GameManager.GAME_MODE_STANDARD);
            } else {
                field |= modeToBitmask(GameManager.GAME_MODE_UNSUPPORTED);
            }
            return field;
        }

        /**
         * Get an array of a package's available game modes.
         */
        public @GameMode int[] getAvailableGameModes() {
            final int modesBitfield = getAvailableGameModesBitfield();
            int[] modes = new int[Integer.bitCount(modesBitfield)];
            int i = 0;
            final int gameModeInHighestBit =
                    Integer.numberOfTrailingZeros(Integer.highestOneBit(modesBitfield));
            for (int mode = 0; mode <= gameModeInHighestBit; ++mode) {
                if (((modesBitfield >> mode) & 1) != 0) {
                    modes[i++] = mode;
                }
            }
            return modes;
        }

        /**
         * Get a GameModeConfiguration for a given game mode.
         *
         * @return The package's GameModeConfiguration for the provided mode or null if absent
         */
        public GameModeConfiguration getGameModeConfiguration(@GameMode int gameMode) {
            return mModeConfigs.get(gameMode);
        }

        /**
         * Insert a new GameModeConfiguration
         */
        public void addModeConfig(GameModeConfiguration config) {
            if (config.isValid()) {
                mModeConfigs.put(config.getGameMode(), config);
            } else {
                Slog.w(TAG, "Invalid game mode config for "
                        + mPackageName + ":" + config.toString());
            }
        }

        public boolean isValid() {
            return mModeConfigs.size() > 0 || mBatteryModeOptedIn || mPerfModeOptedIn;
        }

        public String toString() {
            return "[Name:" + mPackageName + " Modes: " + mModeConfigs.toString() + "]";
        }
    }

    /**
     * SystemService lifecycle for GameService.
     *
     * @hide
     */
    public static class Lifecycle extends SystemService {
        private GameManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            final Context context = getContext();
            mService = new GameManagerService(context);
            publishBinderService(Context.GAME_SERVICE, mService);
            mService.registerDeviceConfigListener();
            mService.registerPackageReceiver();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_BOOT_COMPLETED) {
                mService.onBootCompleted();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            mService.onUserStarting(user);
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mService.onUserUnlocking(user);
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mService.onUserStopping(user);
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            mService.onUserSwitching(from, to);
        }
    }

    private boolean isValidPackageName(String packageName, int userId) {
        try {
            return mPackageManager.getPackageUidAsUser(packageName, userId)
                    == Binder.getCallingUid();
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private void checkPermission(String permission) throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private @GameMode int[] getAvailableGameModesUnchecked(String packageName) {
        GamePackageConfiguration config = null;
        synchronized (mOverrideConfigLock) {
            config = mOverrideConfigs.get(packageName);
        }
        if (config == null) {
            synchronized (mDeviceConfigLock) {
                config = mConfigs.get(packageName);
            }
        }
        if (config == null) {
            return new int[]{};
        }
        return config.getAvailableGameModes();
    }

    private boolean isPackageGame(String packageName, @UserIdInt int userId) {
        try {
            final ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, PackageManager.MATCH_ALL, userId);
            return applicationInfo.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Get an array of game modes available for a given package.
     * Checks that the caller has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int[] getAvailableGameModes(String packageName) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getAvailableGameModesUnchecked(packageName);
    }

    private @GameMode int getGameModeFromSettings(String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                Slog.w(TAG, "User ID '" + userId + "' does not have a Game Mode"
                        + " selected for package: '" + packageName + "'");
                return GameManager.GAME_MODE_UNSUPPORTED;
            }

            return mSettings.get(userId).getGameModeLocked(packageName);
        }
    }

    /**
     * Get the Game Mode for the package name.
     * Verifies that the calling process is for the matching package UID or has
     * {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    public @GameMode int getGameMode(@NonNull String packageName, @UserIdInt int userId)
            throws SecurityException {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameMode",
                "com.android.server.app.GameManagerService");

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            // The game mode for applications that are not identified as game is always
            // UNSUPPORTED. See {@link PackageManager#setApplicationCategoryHint(String, int)}
            return GameManager.GAME_MODE_UNSUPPORTED;
        }

        // This function handles two types of queries:
        // 1) A normal, non-privileged app querying its own Game Mode.
        // 2) A privileged system service querying the Game Mode of another package.
        // The least privileged case is a normal app performing a query, so check that first and
        // return a value if the package name is valid. Next, check if the caller has the necessary
        // permission and return a value. Do this check last, since it can throw an exception.
        if (isValidPackageName(packageName, userId)) {
            return getGameModeFromSettings(packageName, userId);
        }

        // Since the package name doesn't match, check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getGameModeFromSettings(packageName, userId);
    }

    /**
     * Get the GameModeInfo for the package name.
     * Verifies that the calling process is for the matching package UID or has
     * {@link android.Manifest.permission#MANAGE_GAME_MODE}. If the package is not a game,
     * null is always returned.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    @Nullable
    public GameModeInfo getGameModeInfo(@NonNull String packageName, @UserIdInt int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameModeInfo",
                "com.android.server.app.GameManagerService");

        // Check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            return null;
        }

        final @GameMode int activeGameMode = getGameModeFromSettings(packageName, userId);
        final @GameMode int[] availableGameModes = getAvailableGameModesUnchecked(packageName);

        return new GameModeInfo(activeGameMode, availableGameModes);
    }

    /**
     * Sets the Game Mode for the package name.
     * Verifies that the calling process has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameMode(String packageName, @GameMode int gameMode, int userId)
            throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);

        if (!isPackageGame(packageName, userId)) {
            // Restrict to games only.
            return;
        }

        synchronized (mLock) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setGameMode",
                    "com.android.server.app.GameManagerService");

            if (!mSettings.containsKey(userId)) {
                return;
            }
            GameManagerSettings userSettings = mSettings.get(userId);
            userSettings.setGameModeLocked(packageName, gameMode);
            final Message msg = mHandler.obtainMessage(WRITE_SETTINGS);
            msg.obj = userId;
            if (!mHandler.hasEqualMessages(WRITE_SETTINGS, userId)) {
                mHandler.sendMessageDelayed(msg, WRITE_SETTINGS_DELAY);
            }
        }
        updateInterventions(packageName, gameMode, userId);
        final Message msg = mHandler.obtainMessage(WRITE_GAME_MODE_INTERVENTION_LIST_FILE);
        msg.obj = userId;
        if (!mHandler.hasEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, userId)) {
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Get if ANGLE is enabled for the package for the currently enabled game mode.
     * Checks that the caller has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode boolean isAngleEnabled(String packageName, int userId)
            throws SecurityException {
        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return false;
        }

        synchronized (mDeviceConfigLock) {
            final GamePackageConfiguration config = mConfigs.get(packageName);
            if (config == null) {
                return false;
            }
            GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                    config.getGameModeConfiguration(gameMode);
            if (gameModeConfiguration == null) {
                return false;
            }
            return gameModeConfiguration.getUseAngle();
        }
    }

    /**
     * If loading boost is applicable for the package for the currently enabled game mode, return
     * the boost duration. If no configuration is available for the selected package or mode, the
     * default is returned.
     */
    @VisibleForTesting
    public int getLoadingBoostDuration(String packageName, int userId)
            throws SecurityException {
        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return -1;
        }

        synchronized (mDeviceConfigLock) {
            final GamePackageConfiguration config = mConfigs.get(packageName);
            if (config == null) {
                return -1;
            }
            GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                    config.getGameModeConfiguration(gameMode);
            if (gameModeConfiguration == null) {
                return -1;
            }
            return gameModeConfiguration.getLoadingBoostDuration();
        }
    }

    /**
     * If loading boost is enabled, invoke it.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    @GameMode public void notifyGraphicsEnvironmentSetup(String packageName, int userId)
            throws SecurityException {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "notifyGraphicsEnvironmentSetup",
                "com.android.server.app.GameManagerService");

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            return;
        }

        if (!isValidPackageName(packageName, userId)) {
            return;
        }

        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return;
        }
        int loadingBoostDuration = getLoadingBoostDuration(packageName, userId);
        if (loadingBoostDuration != -1) {
            if (loadingBoostDuration == 0 || loadingBoostDuration > LOADING_BOOST_MAX_DURATION) {
                loadingBoostDuration = LOADING_BOOST_MAX_DURATION;
            }
            if (mHandler.hasMessages(CANCEL_GAME_LOADING_MODE)) {
                // The loading mode has already been set and is waiting to be unset. It is not
                // required to set the mode again and we should replace the queued cancel
                // instruction.
                mHandler.removeMessages(CANCEL_GAME_LOADING_MODE);
            } else {
                mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, true);
            }

            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(CANCEL_GAME_LOADING_MODE), loadingBoostDuration);
        }
    }

    /**
     * Sets the game service provider to a given package, meant for testing.
     *
     * <p>This setting persists until the next call or until the next reboot.
     *
     * <p>Checks that the caller has {@link android.Manifest.permission#SET_GAME_SERVICE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.SET_GAME_SERVICE)
    public void setGameServiceProvider(@Nullable String packageName) throws SecurityException {
        checkPermission(Manifest.permission.SET_GAME_SERVICE);

        if (mGameServiceController == null) {
            return;
        }

        mGameServiceController.setGameServiceProvider(packageName);
    }

    /**
     * Notified when boot is completed.
     */
    @VisibleForTesting
    void onBootCompleted() {
        Slog.d(TAG, "onBootCompleted");
        if (mGameServiceController != null) {
            mGameServiceController.onBootComplete();
        }
    }

    void onUserStarting(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();

        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                GameManagerSettings userSettings =
                        new GameManagerSettings(Environment.getDataSystemDeDirectory(userId));
                mSettings.put(userId, userSettings);
                userSettings.readPersistentDataLocked();
            }
        }
        final Message msg = mHandler.obtainMessage(POPULATE_GAME_MODE_SETTINGS);
        msg.obj = userId;
        mHandler.sendMessage(msg);

        if (mGameServiceController != null) {
            mGameServiceController.notifyUserStarted(user);
        }
    }

    void onUserUnlocking(@NonNull TargetUser user) {
        if (mGameServiceController != null) {
            mGameServiceController.notifyUserUnlocking(user);
        }
    }

    void onUserStopping(TargetUser user) {
        final int userId = user.getUserIdentifier();

        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final Message msg = mHandler.obtainMessage(REMOVE_SETTINGS);
            msg.obj = userId;
            mHandler.sendMessage(msg);
        }

        if (mGameServiceController != null) {
            mGameServiceController.notifyUserStopped(user);
        }
    }

    void onUserSwitching(TargetUser from, TargetUser to) {
        final int toUserId = to.getUserIdentifier();
        if (from != null) {
            synchronized (mLock) {
                final int fromUserId = from.getUserIdentifier();
                if (mSettings.containsKey(fromUserId)) {
                    final Message msg = mHandler.obtainMessage(REMOVE_SETTINGS);
                    msg.obj = fromUserId;
                    mHandler.sendMessage(msg);
                }
            }
        }
        final Message msg = mHandler.obtainMessage(POPULATE_GAME_MODE_SETTINGS);
        msg.obj = toUserId;
        mHandler.sendMessage(msg);

        if (mGameServiceController != null) {
            mGameServiceController.notifyNewForegroundUser(to);
        }
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public void disableCompatScale(String packageName) {
        final long uid = Binder.clearCallingIdentity();
        try {
            Slog.i(TAG, "Disabling downscale for " + packageName);
            final ArrayMap<Long, PackageOverride> overrides = new ArrayMap<>();
            overrides.put(DOWNSCALED, COMPAT_DISABLED);
            final CompatibilityOverrideConfig changeConfig = new CompatibilityOverrideConfig(
                    overrides);
            try {
                mPlatformCompat.putOverridesOnReleaseBuilds(changeConfig, packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#putOverridesOnReleaseBuilds", e);
            }
        } finally {
            Binder.restoreCallingIdentity(uid);
        }
    }

    /**
     * Remove frame rate override due to mode switch
     */
    private void resetFps(String packageName, @UserIdInt int userId) {
        try {
            final float fps = 0.0f;
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            nativeSetOverrideFrameRate(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }

    private void enableCompatScale(String packageName, long scaleId) {
        final long uid = Binder.clearCallingIdentity();
        try {
            Slog.i(TAG, "Enabling downscale: " + scaleId + " for " + packageName);
            final ArrayMap<Long, PackageOverride> overrides = new ArrayMap<>();
            overrides.put(DOWNSCALED, COMPAT_ENABLED);
            overrides.put(DOWNSCALE_30, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_35, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_40, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_45, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_50, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_55, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_60, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_65, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_70, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_75, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_80, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_85, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_90, COMPAT_DISABLED);
            overrides.put(scaleId, COMPAT_ENABLED);
            final CompatibilityOverrideConfig changeConfig = new CompatibilityOverrideConfig(
                    overrides);
            try {
                mPlatformCompat.putOverridesOnReleaseBuilds(changeConfig, packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#putOverridesOnReleaseBuilds", e);
            }
        } finally {
            Binder.restoreCallingIdentity(uid);
        }
    }

    private void updateCompatModeDownscale(GamePackageConfiguration packageConfig,
            String packageName, @GameMode int gameMode) {

        if (DEBUG) {
            Slog.v(TAG, dumpDeviceConfigs());
        }
        final GamePackageConfiguration.GameModeConfiguration modeConfig =
                packageConfig.getGameModeConfiguration(gameMode);
        if (modeConfig == null) {
            Slog.i(TAG, "Game mode " + gameMode + " not found for " + packageName);
            return;
        }
        long scaleId = modeConfig.getCompatChangeId();
        if (scaleId == 0) {
            Slog.w(TAG, "Invalid downscaling change id " + scaleId + " for "
                    + packageName);
            return;
        }

        enableCompatScale(packageName, scaleId);
    }

    private int modeToBitmask(@GameMode int gameMode) {
        return (1 << gameMode);
    }

    private boolean bitFieldContainsModeBitmask(int bitField, @GameMode int gameMode) {
        return (bitField & modeToBitmask(gameMode)) != 0;
    }

    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    private void updateUseAngle(String packageName, @GameMode int gameMode) {
        // TODO (b/188475576): Nothing to do yet. Remove if it's still empty when we're ready to
        // ship.
    }


    private void updateFps(GamePackageConfiguration packageConfig, String packageName,
            @GameMode int gameMode, @UserIdInt int userId) {
        final GamePackageConfiguration.GameModeConfiguration modeConfig =
                packageConfig.getGameModeConfiguration(gameMode);
        if (modeConfig == null) {
            Slog.d(TAG, "Game mode " + gameMode + " not found for " + packageName);
            return;
        }
        try {
            final float fps = modeConfig.getFps();
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            nativeSetOverrideFrameRate(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }


    private void updateInterventions(String packageName,
            @GameMode int gameMode, @UserIdInt int userId) {
        if (gameMode == GameManager.GAME_MODE_STANDARD
                || gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            disableCompatScale(packageName);
            resetFps(packageName, userId);
            return;
        }
        GamePackageConfiguration packageConfig = null;

        synchronized (mOverrideConfigLock) {
            packageConfig = mOverrideConfigs.get(packageName);
        }

        if (packageConfig == null) {
            synchronized (mDeviceConfigLock) {
                packageConfig = mConfigs.get(packageName);
            }
        }

        if (packageConfig == null) {
            disableCompatScale(packageName);
            Slog.v(TAG, "Package configuration not found for " + packageName);
            return;
        }
        if (packageConfig.willGamePerformOptimizations(gameMode)) {
            return;
        }
        updateCompatModeDownscale(packageConfig, packageName, gameMode);
        updateFps(packageConfig, packageName, gameMode, userId);
        updateUseAngle(packageName, gameMode);
    }

    /**
     * Set the override Game Mode Configuration.
     * Update the config if exists, create one if not.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameMode, String fpsStr, String scaling) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
        }
        // Adding override game mode configuration of the given package name
        synchronized (mOverrideConfigLock) {
            // look for the existing override GamePackageConfiguration
            GamePackageConfiguration overrideConfig = mOverrideConfigs.get(packageName);
            if (overrideConfig == null) {
                overrideConfig = new GamePackageConfiguration(packageName, userId);
                mOverrideConfigs.put(packageName, overrideConfig);
            }

            // modify GameModeConfiguration intervention settings
            GamePackageConfiguration.GameModeConfiguration overrideModeConfig =
                    overrideConfig.getGameModeConfiguration(gameMode);

            if (fpsStr != null) {
                overrideModeConfig.setFpsStr(fpsStr);
            } else {
                overrideModeConfig.setFpsStr(
                        GamePackageConfiguration.GameModeConfiguration.DEFAULT_FPS);
            }
            if (scaling != null) {
                overrideModeConfig.setScaling(scaling);
            } else {
                overrideModeConfig.setScaling(
                        GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING);
            }
            Slog.i(TAG, "Package Name: " + packageName
                    + " FPS: " + String.valueOf(overrideModeConfig.getFps())
                    + " Scaling: " + overrideModeConfig.getScaling());
        }
        setGameMode(packageName, gameMode, userId);
    }

    /**
     * Reset the overridden gameModeConfiguration of the given mode.
     * Remove the override config if game mode is not specified.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void resetGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameModeToReset) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
        }

        // resets GamePackageConfiguration of a given packageName.
        // If a gameMode is specified, only reset the GameModeConfiguration of the gameMode.
        if (gameModeToReset != -1) {
            GamePackageConfiguration overrideConfig = null;
            synchronized (mOverrideConfigLock) {
                overrideConfig = mOverrideConfigs.get(packageName);
            }

            GamePackageConfiguration config = null;
            synchronized (mDeviceConfigLock) {
                config = mConfigs.get(packageName);
            }

            int[] modes = overrideConfig.getAvailableGameModes();

            // First check if the mode to reset exists
            boolean isGameModeExist = false;
            for (int mode : modes) {
                if (gameModeToReset == mode) {
                    isGameModeExist = true;
                }
            }
            if (!isGameModeExist) {
                return;
            }

            // If the game mode to reset is the only mode other than standard mode,
            // The override config is removed.
            if (modes.length <= 2) {
                synchronized (mOverrideConfigLock) {
                    mOverrideConfigs.remove(packageName);
                }
            } else {
                // otherwise we reset the mode by copying the original config.
                overrideConfig.addModeConfig(config.getGameModeConfiguration(gameModeToReset));
            }
        } else {
            synchronized (mOverrideConfigLock) {
                // remove override config if there is one
                mOverrideConfigs.remove(packageName);
            }
        }

        // Make sure after resetting the game mode is still supported.
        // If not, set the game mode to standard
        int gameMode = getGameMode(packageName, userId);

        GamePackageConfiguration config = null;
        synchronized (mOverrideConfigLock) {
            config = mOverrideConfigs.get(packageName);
        }
        if (config == null) {
            synchronized (mDeviceConfigLock) {
                config = mConfigs.get(packageName);
            }
        }
        final int newGameMode = getNewGameMode(gameMode, config);
        if (gameMode != newGameMode) {
            setGameMode(packageName, GameManager.GAME_MODE_STANDARD, userId);
            return;
        }
        setGameMode(packageName, gameMode, userId);
    }

    private int getNewGameMode(int gameMode, GamePackageConfiguration config) {
        int newGameMode = gameMode;
        if (config != null) {
            int modesBitfield = config.getAvailableGameModesBitfield();
            // Remove UNSUPPORTED to simplify the logic here, since we really just
            // want to check if we support selectable game modes
            modesBitfield &= ~modeToBitmask(GameManager.GAME_MODE_UNSUPPORTED);
            if (!bitFieldContainsModeBitmask(modesBitfield, gameMode)) {
                if (bitFieldContainsModeBitmask(modesBitfield,
                        GameManager.GAME_MODE_STANDARD)) {
                    // If the current set mode isn't supported,
                    // but we support STANDARD, then set the mode to STANDARD.
                    newGameMode = GameManager.GAME_MODE_STANDARD;
                } else {
                    // If we don't support any game modes, then set to UNSUPPORTED
                    newGameMode = GameManager.GAME_MODE_UNSUPPORTED;
                }
            }
        } else if (gameMode != GameManager.GAME_MODE_UNSUPPORTED) {
            // If we have no config for the package, but the configured mode is not
            // UNSUPPORTED, then set to UNSUPPORTED
            newGameMode = GameManager.GAME_MODE_UNSUPPORTED;
        }
        return newGameMode;
    }

    /**
     * Returns the string listing all the interventions currently set to a game.
     */
    public String getInterventionList(String packageName) {
        GamePackageConfiguration packageConfig = null;
        synchronized (mOverrideConfigLock) {
            packageConfig = mOverrideConfigs.get(packageName);
        }

        if (packageConfig == null) {
            synchronized (mDeviceConfigLock) {
                packageConfig = mConfigs.get(packageName);
            }
        }

        StringBuilder listStrSb = new StringBuilder();
        if (packageConfig == null) {
            listStrSb.append("\n No intervention found for package ")
                    .append(packageName);
            return listStrSb.toString();
        }
        listStrSb.append("\n")
                .append(packageConfig.toString());
        return listStrSb.toString();
    }

    /**
     * @hide
     */
    @VisibleForTesting
    void updateConfigsForUser(@UserIdInt int userId, String... packageNames) {
        try {
            synchronized (mDeviceConfigLock) {
                for (final String packageName : packageNames) {
                    final GamePackageConfiguration config =
                            new GamePackageConfiguration(packageName, userId);
                    if (config.isValid()) {
                        if (DEBUG) {
                            Slog.i(TAG, "Adding config: " + config.toString());
                        }
                        mConfigs.put(packageName, config);
                    } else {
                        Slog.w(TAG, "Invalid package config for "
                                + config.getPackageName() + ":" + config.toString());
                        mConfigs.remove(packageName);
                    }
                }
            }
            synchronized (mLock) {
                if (!mSettings.containsKey(userId)) {
                    return;
                }
            }
            for (final String packageName : packageNames) {
                int gameMode = getGameMode(packageName, userId);
                // Make sure the user settings and package configs don't conflict.
                // I.e. the user setting is set to a mode that no longer available due to
                // config/manifest changes.
                // Most of the time we won't have to change anything.
                GamePackageConfiguration config = null;
                synchronized (mDeviceConfigLock) {
                    config = mConfigs.get(packageName);
                }
                final int newGameMode = getNewGameMode(gameMode, config);
                if (newGameMode != gameMode) {
                    setGameMode(packageName, newGameMode, userId);
                } else {
                    // Make sure we handle the case when the interventions are changed while
                    // the game mode remains the same. We call only updateInterventions() here.
                    updateInterventions(packageName, gameMode, userId);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to update compat modes for user " + userId + ": " + e);
        }

        final Message msg = mHandler.obtainMessage(WRITE_GAME_MODE_INTERVENTION_LIST_FILE);
        msg.obj = userId;
        if (!mHandler.hasEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, userId)) {
            mHandler.sendMessage(msg);
        }
    }

    /*
     Write the interventions and mode of each game to file /system/data/game_mode_intervention.list
     Each line will contain the information of each game, separated by tab.
     The format of the output is:
     <package name> <UID> <current mode> <game mode 1> <interventions> <game mode 2> <interventions>
     For example:
     com.android.app1   1425    1   2   angle=0,scaling=1.0,fps=60  3   angle=1,scaling=0.5,fps=30
     */
    private void writeGameModeInterventionsToFile(@UserIdInt int userId) {
        FileOutputStream fileOutputStream = null;
        BufferedWriter bufferedWriter;
        try {
            fileOutputStream = mGameModeInterventionListFile.startWrite();
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(fileOutputStream,
                    Charset.defaultCharset()));

            final StringBuilder sb = new StringBuilder();
            final List<String> installedGamesList = getInstalledGamePackageNamesByAllUsers(userId);
            for (final String packageName : installedGamesList) {
                GamePackageConfiguration packageConfig = getConfig(packageName);
                if (packageConfig == null) {
                    continue;
                }
                sb.append(packageName);
                sb.append("\t");
                sb.append(mPackageManager.getPackageUidAsUser(packageName, userId));
                sb.append("\t");
                sb.append(getGameMode(packageName, userId));
                sb.append("\t");
                final int[] modes = packageConfig.getAvailableGameModes();
                for (int mode : modes) {
                    final GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                            packageConfig.getGameModeConfiguration(mode);
                    if (gameModeConfiguration == null) {
                        continue;
                    }
                    sb.append(mode);
                    sb.append("\t");
                    final int useAngle = gameModeConfiguration.getUseAngle() ? 1 : 0;
                    sb.append(TextUtils.formatSimple("angle=%d", useAngle));
                    sb.append(",");
                    final String scaling = gameModeConfiguration.getScaling();
                    sb.append("scaling=");
                    sb.append(scaling);
                    sb.append(",");
                    final int fps = gameModeConfiguration.getFps();
                    sb.append(TextUtils.formatSimple("fps=%d", fps));
                    sb.append("\t");
                }
                sb.append("\n");
            }
            bufferedWriter.append(sb);
            bufferedWriter.flush();
            FileUtils.sync(fileOutputStream);
            mGameModeInterventionListFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            mGameModeInterventionListFile.failWrite(fileOutputStream);
            Slog.wtf(TAG, "Failed to write game_mode_intervention.list, exception " + e);
        }
        return;
    }

    private int[] getAllUserIds(@UserIdInt int currentUserId) {
        final List<UserInfo> users = mUserManager.getUsers();
        int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; ++i) {
            userIds[i] = users.get(i).id;
        }
        if (currentUserId != -1) {
            userIds = ArrayUtils.appendInt(userIds, currentUserId);
        }
        return userIds;
    }

    private String[] getInstalledGamePackageNames(@UserIdInt int userId) {
        final List<PackageInfo> packages =
                mPackageManager.getInstalledPackagesAsUser(0, userId);
        return packages.stream().filter(e -> e.applicationInfo != null && e.applicationInfo.category
                        == ApplicationInfo.CATEGORY_GAME)
                .map(e -> e.packageName)
                .toArray(String[]::new);
    }

    private List<String> getInstalledGamePackageNamesByAllUsers(@UserIdInt int currentUserId) {
        HashSet<String> packageSet = new HashSet<>();

        final int[] userIds = getAllUserIds(currentUserId);
        for (int userId : userIds) {
            packageSet.addAll(Arrays.asList(getInstalledGamePackageNames(userId)));
        }

        return new ArrayList<>(packageSet);
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public GamePackageConfiguration getConfig(String packageName) {
        GamePackageConfiguration packageConfig = null;
        synchronized (mOverrideConfigLock) {
            packageConfig = mOverrideConfigs.get(packageName);
        }
        if (packageConfig == null) {
            synchronized (mDeviceConfigLock) {
                packageConfig = mConfigs.get(packageName);
            }
        }
        return packageConfig;
    }

    private void registerPackageReceiver() {
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(ACTION_PACKAGE_ADDED);
        packageFilter.addAction(ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
                final Uri data = intent.getData();
                try {
                    final int userId = getSendingUserId();
                    if (userId != ActivityManager.getCurrentUser()) {
                        return;
                    }
                    final String packageName = data.getSchemeSpecificPart();
                    try {
                        final ApplicationInfo applicationInfo = mPackageManager
                                .getApplicationInfoAsUser(
                                        packageName, PackageManager.MATCH_ALL, userId);
                        if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) {
                            return;
                        }
                    } catch (NameNotFoundException e) {
                        // Ignore the exception.
                    }
                    switch (intent.getAction()) {
                        case ACTION_PACKAGE_ADDED:
                            updateConfigsForUser(userId, packageName);
                            break;
                        case ACTION_PACKAGE_REMOVED:
                            disableCompatScale(packageName);
                            // If EXTRA_REPLACING is true, it means there will be an
                            // ACTION_PACKAGE_ADDED triggered after this because this
                            // is an updated package that gets installed. Hence, disable
                            // resolution downscaling effort but avoid removing the server
                            // or commandline overriding configurations because those will
                            // not change but the package game mode configurations may change
                            // which may opt in and/or opt out some game mode configurations.
                            if (!intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                                synchronized (mOverrideConfigLock) {
                                    mOverrideConfigs.remove(packageName);
                                }
                                synchronized (mDeviceConfigLock) {
                                    mConfigs.remove(packageName);
                                }
                                synchronized (mLock) {
                                    if (mSettings.containsKey(userId)) {
                                        mSettings.get(userId).removeGame(packageName);
                                    }
                                }
                            }
                            break;
                        default:
                            // do nothing
                            break;
                    }
                } catch (NullPointerException e) {
                    Slog.e(TAG, "Failed to get package name for new package");
                }
            }
        };
        mContext.registerReceiverForAllUsers(packageReceiver, packageFilter,
                /* broadcastPermission= */ null, /* scheduler= */ null);
    }

    private void registerDeviceConfigListener() {
        mDeviceConfigListener = new DeviceConfigListener();
    }

    private String dumpDeviceConfigs() {
        StringBuilder out = new StringBuilder();
        for (String key : mConfigs.keySet()) {
            out.append("[\nName: ").append(key)
                    .append("\nConfig: ").append(mConfigs.get(key).toString()).append("\n]");
        }
        return out.toString();
    }

    private static int gameStateModeToStatsdGameState(int mode) {
        switch (mode) {
            case GameState.MODE_NONE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_NONE;
            case GameState.MODE_GAMEPLAY_INTERRUPTIBLE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_GAMEPLAY_INTERRUPTIBLE;
            case GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_GAMEPLAY_UNINTERRUPTIBLE;
            case GameState.MODE_CONTENT:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_CONTENT;
            case GameState.MODE_UNKNOWN:
            default:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_UNKNOWN;
        }
    }

    private static ServiceThread createServiceThread() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }

    /**
     * load dynamic library for frame rate overriding JNI calls
     */
    private static native void nativeSetOverrideFrameRate(int uid, float frameRate);
}
