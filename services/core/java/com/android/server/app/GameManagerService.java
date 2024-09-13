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
import static android.server.app.Flags.gameDefaultFrameRate;
import static android.server.app.Flags.disableGameModeWhenAppTop;

import static com.android.internal.R.styleable.GameModeConfig_allowGameAngleDriver;
import static com.android.internal.R.styleable.GameModeConfig_allowGameDownscaling;
import static com.android.internal.R.styleable.GameModeConfig_allowGameFpsOverride;
import static com.android.internal.R.styleable.GameModeConfig_supportsBatteryGameMode;
import static com.android.internal.R.styleable.GameModeConfig_supportsPerformanceGameMode;
import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_GAME;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameManager.GameMode;
import android.app.GameManagerInternal;
import android.app.GameModeConfiguration;
import android.app.GameModeInfo;
import android.app.GameState;
import android.app.IGameManagerService;
import android.app.IGameModeListener;
import android.app.IGameStateListener;
import android.app.StatsManager;
import android.app.UidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.CompatibilityInfo.CompatScale;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.StatsEvent;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.CompatScaleProvider;

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
import java.util.Map;
import java.util.Set;

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
    // event strings used for logging
    private static final String EVENT_SET_GAME_MODE = "SET_GAME_MODE";
    private static final String EVENT_UPDATE_CUSTOM_GAME_MODE_CONFIG =
            "UPDATE_CUSTOM_GAME_MODE_CONFIG";
    private static final String EVENT_RECEIVE_SHUTDOWN_INDENT = "RECEIVE_SHUTDOWN_INDENT";
    private static final String EVENT_ON_USER_STARTING = "ON_USER_STARTING";
    private static final String EVENT_ON_USER_SWITCHING = "ON_USER_SWITCHING";
    private static final String EVENT_ON_USER_STOPPING = "ON_USER_STOPPING";

    static final int WRITE_SETTINGS = 1;
    static final int REMOVE_SETTINGS = 2;
    static final int POPULATE_GAME_MODE_SETTINGS = 3;
    static final int SET_GAME_STATE = 4;
    static final int CANCEL_GAME_LOADING_MODE = 5;
    static final int WRITE_GAME_MODE_INTERVENTION_LIST_FILE = 6;
    static final int WRITE_DELAY_MILLIS = 10 * 1000;  // 10 seconds
    static final int LOADING_BOOST_MAX_DURATION = 5 * 1000;  // 5 seconds
    static final String PROPERTY_DEBUG_GFX_GAME_DEFAULT_FRAME_RATE_DISABLED =
            "debug.graphics.game_default_frame_rate.disabled";
    static final String PROPERTY_RO_SURFACEFLINGER_GAME_DEFAULT_FRAME_RATE =
            "ro.surface_flinger.game_default_frame_rate_override";

    private static final String PACKAGE_NAME_MSG_KEY = "packageName";
    private static final String USER_ID_MSG_KEY = "userId";
    private static final String GAME_MODE_INTERVENTION_LIST_FILE_NAME =
            "game_mode_intervention.list";

    private final Context mContext;
    private final Object mLock = new Object();
    private final Object mDeviceConfigLock = new Object();
    private final Object mGameModeListenerLock = new Object();
    private final Object mGameStateListenerLock = new Object();
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    final Handler mHandler;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final PowerManagerInternal mPowerManagerInternal;
    @VisibleForTesting
    final AtomicFile mGameModeInterventionListFile;
    private DeviceConfigListener mDeviceConfigListener;
    @GuardedBy("mLock")
    private final ArrayMap<Integer, GameManagerSettings> mSettings = new ArrayMap<>();
    @GuardedBy("mDeviceConfigLock")
    private final ArrayMap<String, GamePackageConfiguration> mConfigs = new ArrayMap<>();
    // listener to caller uid map
    @GuardedBy("mGameModeListenerLock")
    private final ArrayMap<IGameModeListener, Integer> mGameModeListeners = new ArrayMap<>();
    @GuardedBy("mGameStateListenerLock")
    private final ArrayMap<IGameStateListener, Integer> mGameStateListeners = new ArrayMap<>();
    @Nullable
    private final GameServiceController mGameServiceController;
    private final Object mUidObserverLock = new Object();
    @VisibleForTesting
    @Nullable
    final MyUidObserver mUidObserver;
    @GuardedBy("mUidObserverLock")
    private final Set<Integer> mGameForegroundUids = new HashSet<>();
    @GuardedBy("mUidObserverLock")
    private final Set<Integer> mNonGameForegroundUids = new HashSet<>();
    private final GameManagerServiceSystemPropertiesWrapper mSysProps;
    private float mGameDefaultFrameRateValue;

    @VisibleForTesting
    static class Injector {
        public GameManagerServiceSystemPropertiesWrapper createSystemPropertiesWrapper() {
            return new GameManagerServiceSystemPropertiesWrapper() {
                @Override
                public String get(String key, String def) {
                    return SystemProperties.get(key, def);
                }
                @Override
                public boolean getBoolean(String key, boolean def) {
                    return SystemProperties.getBoolean(key, def);
                }

                @Override
                public int getInt(String key, int def) {
                    return SystemProperties.getInt(key, def);
                }

                @Override
                public void set(String key, String val) {
                    SystemProperties.set(key, val);
                }
            };
        }
    }

    public GameManagerService(Context context) {
        this(context, createServiceThread().getLooper());
    }

    GameManagerService(Context context, Looper looper) {
        this(context, looper, Environment.getDataDirectory(), new Injector());
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    GameManagerService(Context context, Looper looper, File dataDir, Injector injector) {
        super(PermissionEnforcer.fromContext(context));
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        FileUtils.setPermissions(systemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mGameModeInterventionListFile = new AtomicFile(new File(systemDir,
                GAME_MODE_INTERVENTION_LIST_FILE_NAME));
        FileUtils.setPermissions(mGameModeInterventionListFile.getBaseFile().getAbsolutePath(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR
                        | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                -1, -1);
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_GAME_SERVICE)) {
            mGameServiceController = new GameServiceController(
                    context, BackgroundThread.getExecutor(),
                    new GameServiceProviderSelectorImpl(context.getResources(), mPackageManager),
                    new GameServiceProviderInstanceFactoryImpl(context));
        } else {
            mGameServiceController = null;
        }
        mUidObserver = new MyUidObserver();
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not register UidObserver");
        }

        mSysProps = injector.createSystemPropertiesWrapper();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new GameManagerShellCommand(mPackageManager).exec(this, in, out, err, args, callback,
                result);
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
            pw.println(getInterventionList(packageName, userId));
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
                            removeEqualMessages(WRITE_SETTINGS, msg.obj);
                        }
                        break;
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mLock) {
                        removeEqualMessages(WRITE_SETTINGS, msg.obj);
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
                            removeEqualMessages(WRITE_SETTINGS, msg.obj);
                            removeEqualMessages(REMOVE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    synchronized (mLock) {
                        // Since the user was removed, ignore previous write message
                        // and do write here.
                        removeEqualMessages(WRITE_SETTINGS, msg.obj);
                        removeEqualMessages(REMOVE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            final GameManagerSettings userSettings = mSettings.get(userId);
                            mSettings.remove(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    break;
                }
                case POPULATE_GAME_MODE_SETTINGS: {
                    removeEqualMessages(POPULATE_GAME_MODE_SETTINGS, msg.obj);
                    final int userId = (int) msg.obj;
                    final String[] packageNames = getInstalledGamePackageNames(userId);
                    updateConfigsForUser(userId, false /*checkGamePackage*/, packageNames);
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
                        if (mHandler.hasMessages(CANCEL_GAME_LOADING_MODE)) {
                            mHandler.removeMessages(CANCEL_GAME_LOADING_MODE);
                        }
                        Slog.v(TAG, String.format(
                                "Game loading power mode %s (game state change isLoading=%b)",
                                        isLoading ? "ON" : "OFF", isLoading));
                        mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, isLoading);
                        if (isLoading) {
                            int loadingBoostDuration = getLoadingBoostDuration(packageName, userId);
                            loadingBoostDuration = loadingBoostDuration > 0 ? loadingBoostDuration
                                    : LOADING_BOOST_MAX_DURATION;
                            mHandler.sendMessageDelayed(
                                    mHandler.obtainMessage(CANCEL_GAME_LOADING_MODE),
                                    loadingBoostDuration);
                        }
                    }
                    synchronized (mGameStateListenerLock) {
                        for (IGameStateListener listener : mGameStateListeners.keySet()) {
                            try {
                                listener.onGameStateChanged(packageName, gameState, userId);
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Cannot notify game state change for listener added by "
                                        + mGameStateListeners.get(listener));
                            }
                        }
                    }
                    break;
                }
                case CANCEL_GAME_LOADING_MODE: {
                    Slog.v(TAG, "Game loading power mode OFF (loading boost ended)");
                    mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, false);
                    break;
                }
                case WRITE_GAME_MODE_INTERVENTION_LIST_FILE: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write setting for invalid user: " + userId);
                        synchronized (mLock) {
                            removeEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, msg.obj);
                        }
                        break;
                    }

                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    removeEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, msg.obj);
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
            Slog.v(TAG, "Device config changed for packages: " + Arrays.toString(packageNames));
            updateConfigsForUser(ActivityManager.getCurrentUser(), true /*checkGamePackage*/,
                    packageNames);
        }

        @Override
        public void finalize() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }
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
            Slog.d(TAG, "No-op for attempt to set game state for non-game app: " + packageName);
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
    public static class GamePackageConfiguration {
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
        private final Object mModeConfigLock = new Object();
        @GuardedBy("mModeConfigLock")
        private final ArrayMap<Integer, GameModeConfiguration> mModeConfigs = new ArrayMap<>();
        // if adding new properties or make any of the below overridable, the method
        // copyAndApplyOverride should be updated accordingly
        private boolean mPerfModeOverridden = false;
        private boolean mBatteryModeOverridden = false;
        private boolean mAllowDownscale = true;
        private boolean mAllowAngle = true;
        private boolean mAllowFpsOverride = true;

        GamePackageConfiguration(String packageName) {
            mPackageName = packageName;
        }

        GamePackageConfiguration(PackageManager packageManager, String packageName, int userId) {
            mPackageName = packageName;

            try {
                final ApplicationInfo ai = packageManager.getApplicationInfoAsUser(packageName,
                        PackageManager.GET_META_DATA, userId);
                if (!parseInterventionFromXml(packageManager, ai, packageName)
                            && ai.metaData != null) {
                    mPerfModeOverridden = ai.metaData.getBoolean(METADATA_PERFORMANCE_MODE_ENABLE);
                    mBatteryModeOverridden = ai.metaData.getBoolean(METADATA_BATTERY_MODE_ENABLE);
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

        private boolean parseInterventionFromXml(PackageManager packageManager, ApplicationInfo ai,
                String packageName) {
            boolean xmlFound = false;
            try (XmlResourceParser parser = ai.loadXmlMetaData(packageManager,
                    METADATA_GAME_MODE_CONFIG)) {
                if (parser == null) {
                    Slog.v(TAG, "No " + METADATA_GAME_MODE_CONFIG
                            + " meta-data found for package " + mPackageName);
                } else {
                    xmlFound = true;
                    final Resources resources = packageManager.getResourcesForApplication(
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
                        mPerfModeOverridden = array.getBoolean(
                                GameModeConfig_supportsPerformanceGameMode, false);
                        mBatteryModeOverridden = array.getBoolean(
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
                mPerfModeOverridden = false;
                mBatteryModeOverridden = false;
                mAllowDownscale = true;
                mAllowAngle = true;
                mAllowFpsOverride = true;
                Slog.e(TAG, "Error while parsing XML meta-data for "
                        + METADATA_GAME_MODE_CONFIG);
            }
            return xmlFound;
        }

        GameModeConfiguration getOrAddDefaultGameModeConfiguration(int gameMode) {
            synchronized (mModeConfigLock) {
                mModeConfigs.putIfAbsent(gameMode, new GameModeConfiguration(gameMode));
                return mModeConfigs.get(gameMode);
            }
        }

        // used to check if the override package config has any game mode config, if not, it's
        // considered empty and safe to delete from settings
        boolean hasActiveGameModeConfig() {
            synchronized (mModeConfigLock) {
                return !mModeConfigs.isEmpty();
            }
        }

        /**
         * GameModeConfiguration contains all the values for all the interventions associated with
         * a game mode.
         */
        public class GameModeConfiguration {
            public static final String TAG = "GameManagerService_GameModeConfiguration";
            public static final String MODE_KEY = "mode";
            public static final String SCALING_KEY = "downscaleFactor";
            public static final String FPS_KEY = "fps";
            public static final String ANGLE_KEY = "useAngle";
            public static final String LOADING_BOOST_KEY = "loadingBoost";

            public static final float DEFAULT_SCALING = -1f;
            public static final String DEFAULT_FPS = "";
            public static final boolean DEFAULT_USE_ANGLE = false;
            public static final int DEFAULT_LOADING_BOOST_DURATION = -1;

            private final @GameMode int mGameMode;
            private float mScaling = DEFAULT_SCALING;
            private String mFps = DEFAULT_FPS;
            private boolean mUseAngle;
            private int mLoadingBoostDuration;

            GameModeConfiguration(int gameMode) {
                mGameMode = gameMode;
                mUseAngle = DEFAULT_USE_ANGLE;
                mLoadingBoostDuration = DEFAULT_LOADING_BOOST_DURATION;
            }

            GameModeConfiguration(KeyValueListParser parser) {
                mGameMode = parser.getInt(MODE_KEY, GameManager.GAME_MODE_UNSUPPORTED);
                // willGamePerformOptimizations() returns if an app will handle all of the changes
                // necessary for a particular game mode. If so, the Android framework (i.e.
                // GameManagerService) will not do anything for the app (like window scaling or
                // using ANGLE).
                mScaling = !mAllowDownscale || willGamePerformOptimizations(mGameMode)
                        ? DEFAULT_SCALING : parser.getFloat(SCALING_KEY, DEFAULT_SCALING);

                mFps = mAllowFpsOverride && !willGamePerformOptimizations(mGameMode)
                        ? parser.getString(FPS_KEY, DEFAULT_FPS) : DEFAULT_FPS;
                // We only want to use ANGLE if:
                // - We're allowed to use ANGLE (the app hasn't opted out via the manifest) AND
                // - The app has not opted in to performing the work itself AND
                // - The Phenotype config has enabled it.
                mUseAngle = mAllowAngle && !willGamePerformOptimizations(mGameMode)
                        && parser.getBoolean(ANGLE_KEY, DEFAULT_USE_ANGLE);

                mLoadingBoostDuration = willGamePerformOptimizations(mGameMode)
                        ? DEFAULT_LOADING_BOOST_DURATION
                        : parser.getInt(LOADING_BOOST_KEY, DEFAULT_LOADING_BOOST_DURATION);
            }

            public int getGameMode() {
                return mGameMode;
            }

            public synchronized float getScaling() {
                return mScaling;
            }

            public synchronized int getFps() {
                try {
                    final int fpsInt = Integer.parseInt(mFps);
                    return fpsInt;
                } catch (NumberFormatException e) {
                    return 0;
                }
            }

            synchronized String getFpsStr() {
                return mFps;
            }

            public synchronized boolean getUseAngle() {
                return mUseAngle;
            }

            public synchronized int getLoadingBoostDuration() {
                return mLoadingBoostDuration;
            }

            public synchronized void setScaling(float scaling) {
                mScaling = scaling;
            }

            public synchronized void setFpsStr(String fpsStr) {
                mFps = fpsStr;
            }

            public synchronized void setUseAngle(boolean useAngle) {
                mUseAngle = useAngle;
            }

            public synchronized void setLoadingBoostDuration(int loadingBoostDuration) {
                mLoadingBoostDuration = loadingBoostDuration;
            }

            public boolean isActive() {
                return (mGameMode == GameManager.GAME_MODE_STANDARD
                        || mGameMode == GameManager.GAME_MODE_PERFORMANCE
                        || mGameMode == GameManager.GAME_MODE_BATTERY
                        || mGameMode == GameManager.GAME_MODE_CUSTOM)
                        && !willGamePerformOptimizations(mGameMode);
            }

            android.app.GameModeConfiguration toPublicGameModeConfig() {
                int fpsOverride;
                try {
                    fpsOverride = Integer.parseInt(mFps);
                } catch (NumberFormatException e) {
                    fpsOverride = 0;
                }
                // TODO(b/243448953): match to proper value in case of display change?
                fpsOverride = fpsOverride > 0 ? fpsOverride
                        : android.app.GameModeConfiguration.FPS_OVERRIDE_NONE;
                final float scaling = mScaling == DEFAULT_SCALING ? 1.0f : mScaling;
                return new android.app.GameModeConfiguration.Builder()
                        .setScalingFactor(scaling)
                        .setFpsOverride(fpsOverride).build();
            }

            void updateFromPublicGameModeConfig(android.app.GameModeConfiguration config) {
                mScaling = config.getScalingFactor();
                mFps = String.valueOf(config.getFpsOverride());
            }

            /**
             * @hide
             */
            public String toString() {
                return "[Game Mode:" + mGameMode + ",Scaling:" + mScaling + ",Use Angle:"
                        + mUseAngle + ",Fps:" + mFps + ",Loading Boost Duration:"
                        + mLoadingBoostDuration + "]";
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
            return (mBatteryModeOverridden && gameMode == GameManager.GAME_MODE_BATTERY)
                    || (mPerfModeOverridden && gameMode == GameManager.GAME_MODE_PERFORMANCE);
        }

        private int getAvailableGameModesBitfield() {
            int field = modeToBitmask(GameManager.GAME_MODE_CUSTOM)
                    | modeToBitmask(GameManager.GAME_MODE_STANDARD);
            synchronized (mModeConfigLock) {
                for (final int mode : mModeConfigs.keySet()) {
                    field |= modeToBitmask(mode);
                }
            }
            if (mBatteryModeOverridden) {
                field |= modeToBitmask(GameManager.GAME_MODE_BATTERY);
            }
            if (mPerfModeOverridden) {
                field |= modeToBitmask(GameManager.GAME_MODE_PERFORMANCE);
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
         * Get an array of a package's overridden game modes.
         */
        public @GameMode int[] getOverriddenGameModes() {
            if (mBatteryModeOverridden && mPerfModeOverridden) {
                return new int[]{GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_PERFORMANCE};
            } else if (mBatteryModeOverridden) {
                return new int[]{GameManager.GAME_MODE_BATTERY};
            } else if (mPerfModeOverridden) {
                return new int[]{GameManager.GAME_MODE_PERFORMANCE};
            } else {
                return new int[]{};
            }
        }

        /**
         * Get a GameModeConfiguration for a given game mode.
         *
         * @return The package's GameModeConfiguration for the provided mode or null if absent
         */
        public GameModeConfiguration getGameModeConfiguration(@GameMode int gameMode) {
            synchronized (mModeConfigLock) {
                return mModeConfigs.get(gameMode);
            }
        }

        /**
         * Inserts a new GameModeConfiguration.
         */
        public void addModeConfig(GameModeConfiguration config) {
            if (config.isActive()) {
                synchronized (mModeConfigLock) {
                    mModeConfigs.put(config.getGameMode(), config);
                }
            } else {
                Slog.w(TAG, "Attempt to add inactive game mode config for "
                        + mPackageName + ":" + config.toString());
            }
        }

        /**
         * Removes the GameModeConfiguration.
         */
        public void removeModeConfig(int mode) {
            synchronized (mModeConfigLock) {
                mModeConfigs.remove(mode);
            }
        }

        public boolean isActive() {
            synchronized (mModeConfigLock) {
                return mModeConfigs.size() > 0 || mBatteryModeOverridden || mPerfModeOverridden;
            }
        }

        GamePackageConfiguration copyAndApplyOverride(GamePackageConfiguration overrideConfig) {
            GamePackageConfiguration copy = new GamePackageConfiguration(mPackageName);
            // if a game mode is overridden, we treat it with the highest priority and reset any
            // overridden game modes so that interventions are always executed.
            copy.mPerfModeOverridden = mPerfModeOverridden && !(overrideConfig != null
                    && overrideConfig.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE)
                    != null);
            copy.mBatteryModeOverridden = mBatteryModeOverridden && !(overrideConfig != null
                    && overrideConfig.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY)
                    != null);

            // if any game mode is overridden, we will consider all interventions forced-active,
            // this can be done more granular by checking if a specific intervention is
            // overridden under each game mode override, but only if necessary.
            copy.mAllowDownscale = mAllowDownscale || overrideConfig != null;
            copy.mAllowAngle = mAllowAngle || overrideConfig != null;
            copy.mAllowFpsOverride = mAllowFpsOverride || overrideConfig != null;
            if (overrideConfig != null) {
                synchronized (copy.mModeConfigLock) {
                    synchronized (mModeConfigLock) {
                        for (Map.Entry<Integer, GameModeConfiguration> entry :
                                mModeConfigs.entrySet()) {
                            copy.mModeConfigs.put(entry.getKey(), entry.getValue());
                        }
                    }
                    synchronized (overrideConfig.mModeConfigLock) {
                        for (Map.Entry<Integer, GameModeConfiguration> entry :
                                overrideConfig.mModeConfigs.entrySet()) {
                            copy.mModeConfigs.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return copy;
        }

        public String toString() {
            synchronized (mModeConfigLock) {
                return "[Name:" + mPackageName + " Modes: " + mModeConfigs.toString() + "]";
            }
        }
    }

    private final class LocalService extends GameManagerInternal implements CompatScaleProvider {
        @Override
        public float getResolutionScalingFactor(String packageName, int userId) {
            final int gameMode = getGameModeFromSettingsUnchecked(packageName, userId);
            return getResolutionScalingFactorInternal(packageName, gameMode, userId);
        }

        @Nullable
        @Override
        public CompatScale getCompatScale(@NonNull String packageName, int uid) {
            UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
            int userId = userHandle.getIdentifier();
            float scalingFactor = getResolutionScalingFactor(packageName, userId);
            if (scalingFactor > 0) {
                return new CompatScale(1f / scalingFactor);
            }
            return null;
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
            mService = new GameManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.GAME_SERVICE, mService);
            mService.publishLocalService();
            mService.registerDeviceConfigListener();
            mService.registerPackageReceiver();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_BOOT_COMPLETED) {
                mService.onBootCompleted();
                mService.registerStatsCallbacks();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            Slog.d(TAG, "Starting user " + user.getUserIdentifier());
            mService.onUserStarting(user,
                    Environment.getDataSystemDeDirectory(user.getUserIdentifier()));
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

    private @GameMode int[] getAvailableGameModesUnchecked(String packageName, int userId) {
        final GamePackageConfiguration config = getConfig(packageName, userId);
        if (config == null) {
            return new int[]{GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM};
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
    public @GameMode int[] getAvailableGameModes(String packageName, int userId)
            throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        if (!isPackageGame(packageName, userId)) {
            return new int[]{};
        }
        return getAvailableGameModesUnchecked(packageName, userId);
    }

    private @GameMode int getGameModeFromSettingsUnchecked(String packageName,
            @UserIdInt int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                Slog.d(TAG, "User ID '" + userId + "' does not have a Game Mode"
                        + " selected for package: '" + packageName + "'");
                return GameManager.GAME_MODE_STANDARD;
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
            return getGameModeFromSettingsUnchecked(packageName, userId);
        }

        // Since the package name doesn't match, check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getGameModeFromSettingsUnchecked(packageName, userId);
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

        if (!isPackageGame(packageName, userId)) {
            return null;
        }

        final @GameMode int activeGameMode = getGameModeFromSettingsUnchecked(packageName, userId);
        final GamePackageConfiguration config = getConfig(packageName, userId);
        if (config != null) {
            final @GameMode int[] overriddenGameModes = config.getOverriddenGameModes();
            final @GameMode int[] availableGameModes = config.getAvailableGameModes();
            GameModeInfo.Builder gameModeInfoBuilder = new GameModeInfo.Builder()
                    .setActiveGameMode(activeGameMode)
                    .setAvailableGameModes(availableGameModes)
                    .setOverriddenGameModes(overriddenGameModes)
                    .setDownscalingAllowed(config.mAllowDownscale)
                    .setFpsOverrideAllowed(config.mAllowFpsOverride);
            for (int gameMode : availableGameModes) {
                if (!config.willGamePerformOptimizations(gameMode)) {
                    GamePackageConfiguration.GameModeConfiguration gameModeConfig =
                            config.getGameModeConfiguration(gameMode);
                    if (gameModeConfig != null) {
                        gameModeInfoBuilder.setGameModeConfiguration(gameMode,
                                gameModeConfig.toPublicGameModeConfig());
                    }
                }
            }
            return gameModeInfoBuilder.build();
        } else {
            return new GameModeInfo.Builder()
                    .setActiveGameMode(activeGameMode)
                    .setAvailableGameModes(getAvailableGameModesUnchecked(packageName, userId))
                    .build();
        }
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
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            Slog.d(TAG, "No-op for attempt to set UNSUPPORTED mode for app: " + packageName);
            return;
        } else if (!isPackageGame(packageName, userId)) {
            Slog.d(TAG, "No-op for attempt to set game mode for non-game app: " + packageName);
            return;
        }
        int fromGameMode;
        synchronized (mLock) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setGameMode",
                    "com.android.server.app.GameManagerService");

            if (!mSettings.containsKey(userId)) {
                Slog.d(TAG, "Failed to set game mode for package " + packageName
                        + " as user " + userId + " is not started");
                return;
            }
            GameManagerSettings userSettings = mSettings.get(userId);
            fromGameMode = userSettings.getGameModeLocked(packageName);
            userSettings.setGameModeLocked(packageName, gameMode);
        }
        updateInterventions(packageName, gameMode, userId);
        synchronized (mGameModeListenerLock) {
            for (IGameModeListener listener : mGameModeListeners.keySet()) {
                Binder.allowBlocking(listener.asBinder());
                try {
                    listener.onGameModeChanged(packageName, fromGameMode, gameMode, userId);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Cannot notify game mode change for listener added by "
                            + mGameModeListeners.get(listener));
                }
            }
        }
        sendUserMessage(userId, WRITE_SETTINGS, EVENT_SET_GAME_MODE, WRITE_DELAY_MILLIS);
        sendUserMessage(userId, WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                EVENT_SET_GAME_MODE, 0 /*delayMillis*/);
        int gameUid = -1;
        try {
            gameUid = mPackageManager.getPackageUidAsUser(packageName, userId);
        } catch (NameNotFoundException ex) {
            Slog.d(TAG, "Cannot find the UID for package " + packageName + " under user " + userId);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.GAME_MODE_CHANGED, gameUid,
                Binder.getCallingUid(), gameModeToStatsdGameMode(fromGameMode),
                gameModeToStatsdGameMode(gameMode));
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
        final GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
            if (config == null) {
                return false;
            }
        }
        GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                config.getGameModeConfiguration(gameMode);
        if (gameModeConfiguration == null) {
            return false;
        }
        return gameModeConfiguration.getUseAngle();
    }

    /**
     * If loading boost is applicable for the package for the currently enabled game mode, return
     * the boost duration. If no configuration is available for the selected package or mode, the
     * default is returned.
     */
    public int getLoadingBoostDuration(String packageName, int userId)
            throws SecurityException {
        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return -1;
        }
        final GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
        }
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

        if (!isValidPackageName(packageName, userId)) {
            Slog.d(TAG, "No-op for attempt to notify graphics env setup for different package"
                    + "than caller with uid: " + Binder.getCallingUid());
            return;
        }

        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            Slog.d(TAG, "No-op for attempt to notify graphics env setup for non-game app: "
                    + packageName);
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
                Slog.v(TAG, "Game loading power mode ON (loading boost on game start)");
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
     * Updates the resolution scaling factor for the package's target game mode and activates it.
     *
     * @param scalingFactor enable scaling override over any other compat scaling if positive,
     *                      or disable the override otherwise
     * @throws SecurityException        if caller doesn't have
     *                                  {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                                  permission.
     * @throws IllegalArgumentException if the user ID provided doesn't exist.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void updateResolutionScalingFactor(String packageName, int gameMode, float scalingFactor,
            int userId) throws SecurityException, IllegalArgumentException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                throw new IllegalArgumentException("User " + userId + " wasn't started");
            }
        }
        setGameModeConfigOverride(packageName, userId, gameMode, null /*fpsStr*/,
                Float.toString(scalingFactor));
    }

    /**
     * Gets the resolution scaling factor for the package's target game mode.
     *
     * @return scaling factor for the game mode if exists or negative value otherwise.
     * @throws SecurityException        if caller doesn't have
     *                                  {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                                  permission.
     * @throws IllegalArgumentException if the user ID provided doesn't exist.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public float getResolutionScalingFactor(String packageName, int gameMode, int userId)
            throws SecurityException, IllegalArgumentException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                throw new IllegalArgumentException("User " + userId + " wasn't started");
            }
        }
        return getResolutionScalingFactorInternal(packageName, gameMode, userId);
    }

    float getResolutionScalingFactorInternal(String packageName, int gameMode, int userId) {
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        if (packageConfig == null) {
            return GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING;
        }
        final GamePackageConfiguration.GameModeConfiguration modeConfig =
                packageConfig.getGameModeConfiguration(gameMode);
        if (modeConfig != null) {
            return modeConfig.getScaling();
        }
        return GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING;
    }

    /**
     * Updates the config for the game's {@link GameManager#GAME_MODE_CUSTOM} mode.
     *
     * @throws SecurityException        if caller doesn't have
     *                                  {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                                  permission.
     * @throws IllegalArgumentException if the user ID provided doesn't exist.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void updateCustomGameModeConfiguration(String packageName,
            GameModeConfiguration gameModeConfig, int userId)
            throws SecurityException, IllegalArgumentException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        if (!isPackageGame(packageName, userId)) {
            Slog.d(TAG, "No-op for attempt to update custom game mode for non-game app: "
                    + packageName);
            return;
        }
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                throw new IllegalArgumentException("User " + userId + " wasn't started");
            }
        }
        // TODO(b/243448953): add validation on gameModeConfig provided
        // Adding game mode config override of the given package name
        GamePackageConfiguration configOverride;
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final GameManagerSettings settings = mSettings.get(userId);
            // look for the existing GamePackageConfiguration override
            configOverride = settings.getConfigOverride(packageName);
            if (configOverride == null) {
                configOverride = new GamePackageConfiguration(packageName);
                settings.setConfigOverride(packageName, configOverride);
            }
        }
        GamePackageConfiguration.GameModeConfiguration internalConfig =
                configOverride.getOrAddDefaultGameModeConfiguration(GameManager.GAME_MODE_CUSTOM);
        final float scalingValueFrom = internalConfig.getScaling();
        final int fpsValueFrom = internalConfig.getFps();
        internalConfig.updateFromPublicGameModeConfig(gameModeConfig);

        sendUserMessage(userId, WRITE_SETTINGS, EVENT_UPDATE_CUSTOM_GAME_MODE_CONFIG,
                WRITE_DELAY_MILLIS);
        sendUserMessage(userId, WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                EVENT_UPDATE_CUSTOM_GAME_MODE_CONFIG, WRITE_DELAY_MILLIS /*delayMillis*/);

        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_CUSTOM) {
            updateInterventions(packageName, gameMode, userId);
        }
        Slog.i(TAG, "Updated custom game mode config for package: " + packageName
                + " with FPS=" + internalConfig.getFps() + ";Scaling="
                + internalConfig.getScaling() + " under user " + userId);

        int gameUid = -1;
        try {
            gameUid = mPackageManager.getPackageUidAsUser(packageName, userId);
        } catch (NameNotFoundException ex) {
            Slog.d(TAG, "Cannot find the UID for package " + packageName + " under user " + userId);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.GAME_MODE_CONFIGURATION_CHANGED, gameUid,
                Binder.getCallingUid(), gameModeToStatsdGameMode(GameManager.GAME_MODE_CUSTOM),
                scalingValueFrom, gameModeConfig.getScalingFactor(),
                fpsValueFrom, gameModeConfig.getFpsOverride());
    }

    /**
     * Adds a game mode listener.
     *
     * @throws SecurityException if caller doesn't have
     *                           {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                           permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void addGameModeListener(@NonNull IGameModeListener listener) {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        try {
            final IBinder listenerBinder = listener.asBinder();
            listenerBinder.linkToDeath(new DeathRecipient() {
                @Override public void binderDied() {
                    // TODO(b/258851194): add traces on binder death based listener removal
                    removeGameModeListenerUnchecked(listener);
                    listenerBinder.unlinkToDeath(this, 0 /*flags*/);
                }
            }, 0 /*flags*/);
            synchronized (mGameModeListenerLock) {
                mGameModeListeners.put(listener, Binder.getCallingUid());
            }
        } catch (RemoteException ex) {
            Slog.e(TAG,
                    "Failed to link death recipient for IGameModeListener from caller "
                            + Binder.getCallingUid() + ", abandoned its listener registration", ex);
        }
    }

    /**
     * Removes a game mode listener.
     *
     * @throws SecurityException if caller doesn't have
     *                           {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                           permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void removeGameModeListener(@NonNull IGameModeListener listener) {
        // TODO(b/258851194): add traces on manual listener removal
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        removeGameModeListenerUnchecked(listener);
    }

    private void removeGameModeListenerUnchecked(IGameModeListener listener) {
        synchronized (mGameModeListenerLock) {
            mGameModeListeners.remove(listener);
        }
    }

    /**
     * Adds a game state listener.
     */
    @Override
    public void addGameStateListener(@NonNull IGameStateListener listener) {
        try {
            final IBinder listenerBinder = listener.asBinder();
            listenerBinder.linkToDeath(new DeathRecipient() {
                @Override public void binderDied() {
                    removeGameStateListenerUnchecked(listener);
                    listenerBinder.unlinkToDeath(this, 0 /*flags*/);
                }
            }, 0 /*flags*/);
            synchronized (mGameStateListenerLock) {
                mGameStateListeners.put(listener, Binder.getCallingUid());
            }
        } catch (RemoteException ex) {
            Slog.e(TAG,
                    "Failed to link death recipient for IGameStateListener from caller "
                            + Binder.getCallingUid() + ", abandoned its listener registration", ex);
        }
    }

    /**
     * Removes a game state listener.
     */
    @Override
    public void removeGameStateListener(@NonNull IGameStateListener listener) {
        removeGameStateListenerUnchecked(listener);
    }

    private void removeGameStateListenerUnchecked(IGameStateListener listener) {
        synchronized (mGameStateListenerLock) {
            mGameStateListeners.remove(listener);
        }
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
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    synchronized (mLock) {
                        // Note that the max wait time of broadcast is 10s (see
                        // {@ShutdownThread#MAX_BROADCAST_TIMEMAX_BROADCAST_TIME}) currently so
                        // this can be optional only if we have message delay plus processing
                        // time significant smaller to prevent data loss.
                        for (Map.Entry<Integer, GameManagerSettings> entry : mSettings.entrySet()) {
                            final int userId = entry.getKey();
                            sendUserMessage(userId, WRITE_SETTINGS,
                                    EVENT_RECEIVE_SHUTDOWN_INDENT, 0 /*delayMillis*/);
                            sendUserMessage(userId,
                                    WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                                    EVENT_RECEIVE_SHUTDOWN_INDENT,
                                    0 /*delayMillis*/);
                        }
                    }
                }
            }
        }, new IntentFilter(Intent.ACTION_SHUTDOWN));
        Slog.v(TAG, "Game loading power mode OFF (game manager service start/restart)");
        mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, false);
        Slog.v(TAG, "Game power mode OFF (game manager service start/restart)");
        mPowerManagerInternal.setPowerMode(Mode.GAME, false);

        mGameDefaultFrameRateValue = (float) mSysProps.getInt(
                PROPERTY_RO_SURFACEFLINGER_GAME_DEFAULT_FRAME_RATE, 60);
        Slog.v(TAG, "Game Default Frame Rate : " + mGameDefaultFrameRateValue);
    }

    private void sendUserMessage(int userId, int what, String eventForLog, int delayMillis) {
        Message msg = mHandler.obtainMessage(what, userId);
        if (!mHandler.sendMessageDelayed(msg, delayMillis)) {
            Slog.e(TAG, "Failed to send user message " + what + " on " + eventForLog);
        }
    }

    void onUserStarting(@NonNull TargetUser user, File settingDataDir) {
        final int userId = user.getUserIdentifier();
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                GameManagerSettings userSettings = new GameManagerSettings(settingDataDir);
                mSettings.put(userId, userSettings);
                userSettings.readPersistentDataLocked();
            }
        }
        sendUserMessage(userId, POPULATE_GAME_MODE_SETTINGS, EVENT_ON_USER_STARTING,
                0 /*delayMillis*/);

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
            sendUserMessage(userId, REMOVE_SETTINGS, EVENT_ON_USER_STOPPING, 0 /*delayMillis*/);
        }

        if (mGameServiceController != null) {
            mGameServiceController.notifyUserStopped(user);
        }
    }

    void onUserSwitching(TargetUser from, TargetUser to) {
        final int toUserId = to.getUserIdentifier();
        // we want to re-populate the setting when switching user as the device config may have
        // changed, which will only update for the previous user, see
        // DeviceConfigListener#onPropertiesChanged.
        sendUserMessage(toUserId, POPULATE_GAME_MODE_SETTINGS, EVENT_ON_USER_SWITCHING,
                0 /*delayMillis*/);

        if (mGameServiceController != null) {
            mGameServiceController.notifyNewForegroundUser(to);
        }
    }

    /**
     * Remove frame rate override due to mode switch
     */
    private void resetFps(String packageName, @UserIdInt int userId) {
        try {
            final float fps = 0.0f;
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            setGameModeFrameRateOverride(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }

    private static int modeToBitmask(@GameMode int gameMode) {
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
            setGameModeFrameRateOverride(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }


    private void updateInterventions(String packageName,
            @GameMode int gameMode, @UserIdInt int userId) {
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_STANDARD
                || gameMode == GameManager.GAME_MODE_UNSUPPORTED || packageConfig == null
                || packageConfig.willGamePerformOptimizations(gameMode)
                || packageConfig.getGameModeConfiguration(gameMode) == null) {
            resetFps(packageName, userId);
            // resolution scaling does not need to be reset as it's now read dynamically on game
            // restart, see #getResolutionScalingFactor and CompatModePackages#getCompatScale.
            // TODO: reset Angle intervention here once implemented
            if (packageConfig == null) {
                Slog.v(TAG, "Package configuration not found for " + packageName);
                return;
            }
        } else {
            updateFps(packageConfig, packageName, gameMode, userId);
        }
        updateUseAngle(packageName, gameMode);
    }

    /**
     * Set the Game Mode Configuration override.
     * Update the config if exists, create one if not.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameMode, String fpsStr, String scaling) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        int gameUid = -1;
        try {
            gameUid = mPackageManager.getPackageUidAsUser(packageName, userId);
        } catch (NameNotFoundException ex) {
            Slog.d(TAG, "Cannot find the UID for package " + packageName + " under user " + userId);
        }
        GamePackageConfiguration pkgConfig = getConfig(packageName, userId);
        if (pkgConfig != null && pkgConfig.getGameModeConfiguration(gameMode) != null) {
            final GamePackageConfiguration.GameModeConfiguration currentModeConfig =
                    pkgConfig.getGameModeConfiguration(gameMode);
            FrameworkStatsLog.write(FrameworkStatsLog.GAME_MODE_CONFIGURATION_CHANGED, gameUid,
                    Binder.getCallingUid(), gameModeToStatsdGameMode(gameMode),
                    currentModeConfig.getScaling() /* fromScaling */,
                    scaling == null ? currentModeConfig.getScaling()
                            : Float.parseFloat(scaling) /* toScaling */,
                    currentModeConfig.getFps() /* fromFps */,
                    fpsStr == null ? currentModeConfig.getFps()
                            : Integer.parseInt(fpsStr)) /* toFps */;
        } else {
            FrameworkStatsLog.write(FrameworkStatsLog.GAME_MODE_CONFIGURATION_CHANGED, gameUid,
                    Binder.getCallingUid(), gameModeToStatsdGameMode(gameMode),
                    GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING /* fromScaling*/,
                    scaling == null ? GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING
                            : Float.parseFloat(scaling) /* toScaling */,
                    0 /* fromFps */,
                    fpsStr == null ? 0 : Integer.parseInt(fpsStr) /* toFps */);
        }

        // Adding game mode config override of the given package name
        GamePackageConfiguration configOverride;
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final GameManagerSettings settings = mSettings.get(userId);
            // look for the existing GamePackageConfiguration override
            configOverride = settings.getConfigOverride(packageName);
            if (configOverride == null) {
                configOverride = new GamePackageConfiguration(packageName);
                settings.setConfigOverride(packageName, configOverride);
            }
        }
        // modify GameModeConfiguration intervention settings
        GamePackageConfiguration.GameModeConfiguration modeConfigOverride =
                configOverride.getOrAddDefaultGameModeConfiguration(gameMode);

        if (fpsStr != null) {
            modeConfigOverride.setFpsStr(fpsStr);
        } else {
            modeConfigOverride.setFpsStr(
                    GamePackageConfiguration.GameModeConfiguration.DEFAULT_FPS);
        }
        if (scaling != null) {
            modeConfigOverride.setScaling(Float.parseFloat(scaling));
        }
        Slog.i(TAG, "Package Name: " + packageName
                + " FPS: " + String.valueOf(modeConfigOverride.getFps())
                + " Scaling: " + modeConfigOverride.getScaling());
        setGameMode(packageName, gameMode, userId);
    }

    /**
     * Reset the overridden gameModeConfiguration of the given mode.
     * Remove the config override if game mode is not specified.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void resetGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameModeToReset) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        // resets GamePackageConfiguration of a given packageName.
        // If a gameMode is specified, only reset the GameModeConfiguration of the gameMode.
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final GameManagerSettings settings = mSettings.get(userId);
            if (gameModeToReset != -1) {
                final GamePackageConfiguration configOverride = settings.getConfigOverride(
                        packageName);
                if (configOverride == null) {
                    return;
                }
                final int modesBitfield = configOverride.getAvailableGameModesBitfield();
                if (!bitFieldContainsModeBitmask(modesBitfield, gameModeToReset)) {
                    return;
                }
                configOverride.removeModeConfig(gameModeToReset);
                if (!configOverride.hasActiveGameModeConfig()) {
                    settings.removeConfigOverride(packageName);
                }
            } else {
                settings.removeConfigOverride(packageName);
            }
        }

        // Make sure after resetting the game mode is still supported.
        // If not, set the game mode to standard
        int gameMode = getGameMode(packageName, userId);

        final GamePackageConfiguration config = getConfig(packageName, userId);
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
                // always default to STANDARD if there is no mode config
                newGameMode = GameManager.GAME_MODE_STANDARD;
            }
        } else {
            // always default to STANDARD if there is no package config
            newGameMode = GameManager.GAME_MODE_STANDARD;
        }
        return newGameMode;
    }

    /**
     * Returns the string listing all the interventions currently set to a game.
     */
    @RequiresPermission(Manifest.permission.QUERY_ALL_PACKAGES)
    public String getInterventionList(String packageName, int userId) {
        checkPermission(Manifest.permission.QUERY_ALL_PACKAGES);
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        final StringBuilder listStrSb = new StringBuilder();
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
    void updateConfigsForUser(@UserIdInt int userId, boolean checkGamePackage,
            String... packageNames) {
        if (checkGamePackage) {
            packageNames = Arrays.stream(packageNames).filter(
                    p -> isPackageGame(p, userId)).toArray(String[]::new);
        }
        try {
            synchronized (mDeviceConfigLock) {
                for (final String packageName : packageNames) {
                    final GamePackageConfiguration config =
                            new GamePackageConfiguration(mPackageManager, packageName, userId);
                    if (config.isActive()) {
                        Slog.v(TAG, "Adding config: " + config.toString());
                        mConfigs.put(packageName, config);
                    } else {
                        Slog.v(TAG, "Inactive package config for "
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
            sendUserMessage(userId, WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                    "UPDATE_CONFIGS_FOR_USERS", 0 /*delayMillis*/);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to update configs for user " + userId + ": " + e);
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
                GamePackageConfiguration packageConfig = getConfig(packageName, userId);
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
                    final float scaling = gameModeConfiguration.getScaling();
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
    public GamePackageConfiguration getConfig(String packageName, int userId) {
        GamePackageConfiguration overrideConfig = null;
        GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
        }

        synchronized (mLock) {
            if (mSettings.containsKey(userId)) {
                overrideConfig = mSettings.get(userId).getConfigOverride(packageName);
            }
        }
        if (overrideConfig == null || config == null) {
            return overrideConfig == null ? config : overrideConfig;
        }
        return config.copyAndApplyOverride(overrideConfig);
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
                            updateConfigsForUser(userId, true /*checkGamePackage*/, packageName);
                            break;
                        case ACTION_PACKAGE_REMOVED:
                            if (!intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                                synchronized (mDeviceConfigLock) {
                                    mConfigs.remove(packageName);
                                }
                                synchronized (mLock) {
                                    if (mSettings.containsKey(userId)) {
                                        mSettings.get(userId).removeGame(packageName);
                                    }
                                    sendUserMessage(userId, WRITE_SETTINGS,
                                            Intent.ACTION_PACKAGE_REMOVED, WRITE_DELAY_MILLIS);
                                    sendUserMessage(userId,
                                            WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                                            Intent.ACTION_PACKAGE_REMOVED, WRITE_DELAY_MILLIS);
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

    private void publishLocalService() {
        LocalService localService = new LocalService();

        ActivityTaskManagerInternal atmi =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        atmi.registerCompatScaleProvider(COMPAT_SCALE_MODE_GAME, localService);

        LocalServices.addService(GameManagerInternal.class, localService);
    }

    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.GAME_MODE_INFO,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.GAME_MODE_CONFIGURATION,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.GAME_MODE_LISTENER,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
    }

    private int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
        if (atomTag == FrameworkStatsLog.GAME_MODE_INFO
                || atomTag == FrameworkStatsLog.GAME_MODE_CONFIGURATION) {
            int userId = ActivityManager.getCurrentUser();
            Set<String> packages;
            synchronized (mDeviceConfigLock) {
                packages = mConfigs.keySet();
            }
            for (String p : packages) {
                GamePackageConfiguration config = getConfig(p, userId);
                if (config == null) {
                    continue;
                }
                int uid = -1;
                try {
                    uid = mPackageManager.getPackageUidAsUser(p, userId);
                } catch (NameNotFoundException ex) {
                    Slog.d(TAG,
                            "Cannot find UID for package " + p + " under user handle id " + userId);
                }
                if (atomTag == FrameworkStatsLog.GAME_MODE_INFO) {
                    data.add(
                            FrameworkStatsLog.buildStatsEvent(FrameworkStatsLog.GAME_MODE_INFO, uid,
                                    gameModesToStatsdGameModes(config.getOverriddenGameModes()),
                                    gameModesToStatsdGameModes(config.getAvailableGameModes())));
                } else if (atomTag == FrameworkStatsLog.GAME_MODE_CONFIGURATION) {
                    for (int gameMode : config.getAvailableGameModes()) {
                        GamePackageConfiguration.GameModeConfiguration modeConfig =
                                config.getGameModeConfiguration(gameMode);
                        if (modeConfig != null) {
                            data.add(FrameworkStatsLog.buildStatsEvent(
                                    FrameworkStatsLog.GAME_MODE_CONFIGURATION, uid,
                                    gameModeToStatsdGameMode(gameMode), modeConfig.getFps(),
                                    modeConfig.getScaling()));
                        }
                    }
                }
            }
        } else if (atomTag == FrameworkStatsLog.GAME_MODE_LISTENER) {
            synchronized (mGameModeListenerLock) {
                data.add(FrameworkStatsLog.buildStatsEvent(FrameworkStatsLog.GAME_MODE_LISTENER,
                        mGameModeListeners.size()));
            }
        }
        return android.app.StatsManager.PULL_SUCCESS;
    }

    private static int[] gameModesToStatsdGameModes(int[] modes) {
        if (modes == null) {
            return null;
        }
        int[] statsdModes = new int[modes.length];
        int i = 0;
        for (int mode : modes) {
            statsdModes[i++] = gameModeToStatsdGameMode(mode);
        }
        return statsdModes;
    }

    private static int gameModeToStatsdGameMode(int mode) {
        switch (mode) {
            case GameManager.GAME_MODE_BATTERY:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_BATTERY;
            case GameManager.GAME_MODE_PERFORMANCE:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_PERFORMANCE;
            case GameManager.GAME_MODE_CUSTOM:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_CUSTOM;
            case GameManager.GAME_MODE_STANDARD:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_STANDARD;
            case GameManager.GAME_MODE_UNSUPPORTED:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_UNSUPPORTED;
            default:
                return FrameworkStatsLog.GAME_MODE_CONFIGURATION__GAME_MODE__GAME_MODE_UNSPECIFIED;
        }
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

    @VisibleForTesting
    void setGameModeFrameRateOverride(int uid, float frameRate) {
        nativeSetGameModeFrameRateOverride(uid, frameRate);
    }

    @VisibleForTesting
    void setGameDefaultFrameRateOverride(int uid, float frameRate) {
        Slog.v(TAG, "setDefaultFrameRateOverride : " + uid + " , " + frameRate);
        nativeSetGameDefaultFrameRateOverride(uid, frameRate);
    }

    private float getGameDefaultFrameRate(boolean isEnabled) {
        float gameDefaultFrameRate = 0.0f;
        if (gameDefaultFrameRate()) {
            gameDefaultFrameRate = isEnabled ? mGameDefaultFrameRateValue : 0.0f;
        }
        return gameDefaultFrameRate;
    }

    @Override
    @EnforcePermission(Manifest.permission.MANAGE_GAME_MODE)
    public void toggleGameDefaultFrameRate(boolean isEnabled) {
        toggleGameDefaultFrameRate_enforcePermission();
        if (gameDefaultFrameRate()) {
            Slog.v(TAG, "toggleGameDefaultFrameRate : " + isEnabled);
            this.toggleGameDefaultFrameRateUnchecked(isEnabled);
        }
    }

    private void toggleGameDefaultFrameRateUnchecked(boolean isEnabled) {
        // Here we only need to immediately update games that are in the foreground.
        // We will update game default frame rate when a game comes into foreground in
        // MyUidObserver.
        synchronized (mLock) {
            if (isEnabled) {
                mSysProps.set(
                        PROPERTY_DEBUG_GFX_GAME_DEFAULT_FRAME_RATE_DISABLED, "false");
            } else {
                mSysProps.set(
                        PROPERTY_DEBUG_GFX_GAME_DEFAULT_FRAME_RATE_DISABLED, "true");
            }
        }

        // Update all foreground games' frame rate.
        synchronized (mUidObserverLock) {
            for (int uid : mGameForegroundUids) {
                setGameDefaultFrameRateOverride(uid, getGameDefaultFrameRate(isEnabled));
            }
        }
    }

    /**
     * load dynamic library for frame rate overriding JNI calls
     */
    private static native void nativeSetGameModeFrameRateOverride(int uid, float frameRate);
    private static native void nativeSetGameDefaultFrameRateOverride(int uid, float frameRate);

    final class MyUidObserver extends UidObserver {
        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (mUidObserverLock) {
                handleUidMovedOffTop(uid);
            }
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            switch (procState) {
                case ActivityManager.PROCESS_STATE_TOP:
                    handleUidMovedToTop(uid);
                    return;
                default:
                    handleUidMovedOffTop(uid);
            }
        }

        private void handleUidMovedToTop(int uid) {
            final String[] packages = mPackageManager.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) {
                return;
            }

            final int userId = ActivityManager.getCurrentUser();
            final boolean isNotGame = Arrays.stream(packages).noneMatch(
                    p -> isPackageGame(p, userId));
            synchronized (mUidObserverLock) {
                if (isNotGame) {
                    if (disableGameModeWhenAppTop()) {
                        if (!mGameForegroundUids.isEmpty() && mNonGameForegroundUids.isEmpty()) {
                            Slog.v(TAG, "Game power mode OFF (first non-game in foreground)");
                            mPowerManagerInternal.setPowerMode(Mode.GAME, false);
                        }
                        mNonGameForegroundUids.add(uid);
                    }
                    return;
                }
                if (mGameForegroundUids.isEmpty() && (!disableGameModeWhenAppTop()
                        || mNonGameForegroundUids.isEmpty())) {
                    Slog.v(TAG, "Game power mode ON (first game in foreground)");
                    mPowerManagerInternal.setPowerMode(Mode.GAME, true);
                }
                final boolean isGameDefaultFrameRateDisabled =
                        mSysProps.getBoolean(
                                PROPERTY_DEBUG_GFX_GAME_DEFAULT_FRAME_RATE_DISABLED, false);
                setGameDefaultFrameRateOverride(uid,
                        getGameDefaultFrameRate(!isGameDefaultFrameRateDisabled));
                mGameForegroundUids.add(uid);
            }
        }

        private void handleUidMovedOffTop(int uid) {
            synchronized (mUidObserverLock) {
                if (mGameForegroundUids.contains(uid)) {
                    mGameForegroundUids.remove(uid);
                    if (mGameForegroundUids.isEmpty() && (!disableGameModeWhenAppTop()
                            || mNonGameForegroundUids.isEmpty())) {
                        Slog.v(TAG, "Game power mode OFF (no games in foreground)");
                        mPowerManagerInternal.setPowerMode(Mode.GAME, false);
                    }
                } else if (disableGameModeWhenAppTop() && mNonGameForegroundUids.contains(uid)) {
                    mNonGameForegroundUids.remove(uid);
                    if (mNonGameForegroundUids.isEmpty() && !mGameForegroundUids.isEmpty()) {
                        Slog.v(TAG, "Game power mode ON (only games in foreground)");
                        mPowerManagerInternal.setPowerMode(Mode.GAME, true);
                    }
                }
            }
        }
    }
}
