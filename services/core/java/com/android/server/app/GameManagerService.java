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
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;

import static com.android.server.wm.CompatModePackages.DOWNSCALED;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_50;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_60;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_70;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_80;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_90;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameManager.GameMode;
import android.app.IGameManagerService;
import android.app.compat.PackageOverride;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
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
    static final int WRITE_SETTINGS_DELAY = 10 * 1000;  // 10 seconds
    static final PackageOverride COMPAT_ENABLED = new PackageOverride.Builder().setEnabled(true)
            .build();
    static final PackageOverride COMPAT_DISABLED = new PackageOverride.Builder().setEnabled(false)
            .build();

    private final Context mContext;
    private final Object mLock = new Object();
    private final Object mDeviceConfigLock = new Object();
    private final Handler mHandler;
    private final PackageManager mPackageManager;
    private final IPlatformCompat mPlatformCompat;
    private DeviceConfigListener mDeviceConfigListener;
    @GuardedBy("mLock")
    private final ArrayMap<Integer, GameManagerSettings> mSettings = new ArrayMap<>();
    @GuardedBy("mDeviceConfigLock")
    private final ArrayMap<String, GamePackageConfiguration> mConfigs = new ArrayMap<>();

    public GameManagerService(Context context) {
        this(context, createServiceThread().getLooper());
    }

    GameManagerService(Context context, Looper looper) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new GameManagerShellCommand().exec(this, in, out, err, args, callback, result);
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
            updateConfigsForUser(mContext.getUserId(), packageNames);
        }

        @Override
        public void finalize() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }
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

        private final String mPackageName;
        private final ArrayMap<Integer, GameModeConfiguration> mModeConfigs;
        private boolean mPerfModeOptedIn;
        private boolean mBatteryModeOptedIn;
        private boolean mAllowDownscale;

        GamePackageConfiguration(String packageName, int userId) {
            mPackageName = packageName;
            mModeConfigs = new ArrayMap<>();
            try {
                final ApplicationInfo ai = mPackageManager.getApplicationInfoAsUser(packageName,
                        PackageManager.GET_META_DATA, userId);
                if (ai.metaData != null) {
                    mPerfModeOptedIn = ai.metaData.getBoolean(METADATA_PERFORMANCE_MODE_ENABLE);
                    mBatteryModeOptedIn = ai.metaData.getBoolean(METADATA_BATTERY_MODE_ENABLE);
                    mAllowDownscale = ai.metaData.getBoolean(METADATA_WM_ALLOW_DOWNSCALE, true);
                } else {
                    mPerfModeOptedIn = false;
                    mBatteryModeOptedIn = false;
                    mAllowDownscale = true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Failed to get package metadata", e);
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

        /**
         * GameModeConfiguration contains all the values for all the interventions associated with
         * a game mode.
         */
        @VisibleForTesting
        public class GameModeConfiguration {
            public static final String TAG = "GameManagerService_GameModeConfiguration";
            public static final String MODE_KEY = "mode";
            public static final String SCALING_KEY = "downscaleFactor";
            public static final String DEFAULT_SCALING = "1.0";

            private final @GameMode int mGameMode;
            private final String mScaling;

            GameModeConfiguration(KeyValueListParser parser) {
                mGameMode = parser.getInt(MODE_KEY, GameManager.GAME_MODE_UNSUPPORTED);
                mScaling = !mAllowDownscale || isGameModeOptedIn(mGameMode)
                        ? DEFAULT_SCALING : parser.getString(SCALING_KEY, DEFAULT_SCALING);
            }

            public int getGameMode() {
                return mGameMode;
            }

            public String getScaling() {
                return mScaling;
            }

            public boolean isValid() {
                return (mGameMode == GameManager.GAME_MODE_PERFORMANCE
                        || mGameMode == GameManager.GAME_MODE_BATTERY)
                        && (!mAllowDownscale || getCompatChangeId() != 0);
            }

            /**
             * @hide
             */
            public String toString() {
                return "[Game Mode:" + mGameMode + ",Scaling:" + mScaling + "]";
            }

            /**
             * Get the corresponding compat change id for the current scaling string.
             */
            public long getCompatChangeId() {
                switch (mScaling) {
                    case "0.5":
                        return DOWNSCALE_50;
                    case "0.6":
                        return DOWNSCALE_60;
                    case "0.7":
                        return DOWNSCALE_70;
                    case "0.8":
                        return DOWNSCALE_80;
                    case "0.9":
                        return DOWNSCALE_90;
                }
                return 0;
            }
        }

        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Gets whether a package has opted into a game mode via its manifest.
         *
         * @return True if the app package has specified in its metadata either:
         * "com.android.app.gamemode.performance.enabled" or
         * "com.android.app.gamemode.battery.enabled" with a value of "true"
         */
        public boolean isGameModeOptedIn(@GameMode int gameMode) {
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
            mService = new GameManagerService(getContext());
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
            mService.onUserStarting(user.getUserIdentifier());
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mService.onUserStopping(user.getUserIdentifier());
        }
    }

    private boolean isValidPackageName(String packageName) {
        try {
            return mPackageManager.getPackageUid(packageName, 0) == Binder.getCallingUid();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
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

    /**
     * Get an array of game modes available for a given package.
     * Checks that the caller has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int[] getAvailableGameModes(String packageName) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mDeviceConfigLock) {
            final GamePackageConfiguration config = mConfigs.get(packageName);
            if (config == null) {
                return new int[]{GameManager.GAME_MODE_UNSUPPORTED};
            }
            return config.getAvailableGameModes();
        }
    }

    private @GameMode int getGameModeFromSettings(String packageName, int userId) {
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
    public @GameMode int getGameMode(String packageName, int userId)
            throws SecurityException {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameMode",
                "com.android.server.app.GameManagerService");

        // Restrict to games only.
        try {
            final ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, PackageManager.MATCH_ALL, userId);
            if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) {
                Slog.e(TAG, "Ignoring attempt to get the Game Mode for '" + packageName
                        + "' which is not categorized as a game: applicationInfo.flags = "
                        + applicationInfo.flags + ", category = " + applicationInfo.category);
                return GameManager.GAME_MODE_UNSUPPORTED;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return GameManager.GAME_MODE_UNSUPPORTED;
        }

        // This function handles two types of queries:
        // 1.) A normal, non-privileged app querying its own Game Mode.
        // 2.) A privileged system service querying the Game Mode of another package.
        // The least privileged case is a normal app performing a query, so check that first and
        // return a value if the package name is valid. Next, check if the caller has the necessary
        // permission and return a value. Do this check last, since it can throw an exception.
        if (isValidPackageName(packageName)) {
            return getGameModeFromSettings(packageName, userId);
        }

        // Since the package name doesn't match, check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getGameModeFromSettings(packageName, userId);
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

        // Restrict to games only.
        try {
            final ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, PackageManager.MATCH_ALL, userId);
            if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) {
                Slog.e(TAG, "Ignoring attempt to set the Game Mode for '" + packageName
                        + "' which is not categorized as a game: applicationInfo.flags = "
                        + applicationInfo.flags + ", category = " + applicationInfo.category);
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
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
        updateCompatModeDownscale(packageName, gameMode);
    }

    /**
     * Notified when boot is completed.
     */
    @VisibleForTesting
    void onBootCompleted() {
        Slog.d(TAG, "onBootCompleted");
    }

    void onUserStarting(int userId) {
        synchronized (mLock) {
            if (mSettings.containsKey(userId)) {
                return;
            }

            GameManagerSettings userSettings =
                    new GameManagerSettings(Environment.getDataSystemDeDirectory(userId));
            mSettings.put(userId, userSettings);
            userSettings.readPersistentDataLocked();
        }
        final Message msg = mHandler.obtainMessage(POPULATE_GAME_MODE_SETTINGS);
        msg.obj = userId;
        mHandler.sendMessage(msg);
    }

    void onUserStopping(int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final Message msg = mHandler.obtainMessage(REMOVE_SETTINGS);
            msg.obj = userId;
            mHandler.sendMessage(msg);
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
                mPlatformCompat.setOverridesOnReleaseBuilds(changeConfig, packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#setOverridesOnReleaseBuilds", e);
            }
        } finally {
            Binder.restoreCallingIdentity(uid);
        }
    }

    private void enableCompatScale(String packageName, long scaleId) {
        final long uid = Binder.clearCallingIdentity();
        try {
            Slog.i(TAG, "Enabling downscale: " + scaleId + " for " + packageName);
            final ArrayMap<Long, PackageOverride> overrides = new ArrayMap<>();
            overrides.put(DOWNSCALED, COMPAT_ENABLED);
            overrides.put(DOWNSCALE_50, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_60, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_70, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_80, COMPAT_DISABLED);
            overrides.put(DOWNSCALE_90, COMPAT_DISABLED);
            overrides.put(scaleId, COMPAT_ENABLED);
            final CompatibilityOverrideConfig changeConfig = new CompatibilityOverrideConfig(
                    overrides);
            try {
                mPlatformCompat.setOverridesOnReleaseBuilds(changeConfig, packageName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#setOverridesOnReleaseBuilds", e);
            }
        } finally {
            Binder.restoreCallingIdentity(uid);
        }
    }

    private void updateCompatModeDownscale(String packageName, @GameMode int gameMode) {
        synchronized (mDeviceConfigLock) {
            if (gameMode == GameManager.GAME_MODE_STANDARD
                    || gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
                disableCompatScale(packageName);
                return;
            }
            final GamePackageConfiguration packageConfig = mConfigs.get(packageName);
            if (packageConfig == null) {
                disableCompatScale(packageName);
                Slog.v(TAG, "Package configuration not found for " + packageName);
                return;
            }
            if (DEBUG) {
                Slog.v(TAG, dumpDeviceConfigs());
            }
            if (packageConfig.isGameModeOptedIn(gameMode)) {
                disableCompatScale(packageName);
                return;
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
    }

    private int modeToBitmask(@GameMode int gameMode) {
        return (1 << gameMode);
    }

    private boolean bitFieldContainsModeBitmask(int bitField, @GameMode int gameMode) {
        return (bitField & modeToBitmask(gameMode)) != 0;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public void updateConfigsForUser(int userId, String ...packageNames) {
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
            for (final String packageName : packageNames) {
                if (mSettings.containsKey(userId)) {
                    int gameMode = getGameMode(packageName, userId);
                    int newGameMode = gameMode;
                    // Make sure the user settings and package configs don't conflict. I.e. the
                    // user setting is set to a mode that no longer available due to config/manifest
                    // changes. Most of the time we won't have to change anything.
                    GamePackageConfiguration config;
                    synchronized (mDeviceConfigLock) {
                        config = mConfigs.get(packageName);
                    }
                    if (config != null) {
                        int modesBitfield = config.getAvailableGameModesBitfield();
                        // Remove UNSUPPORTED to simplify the logic here, since we really just
                        // want to check if we support selectable game modes
                        modesBitfield &= ~modeToBitmask(GameManager.GAME_MODE_UNSUPPORTED);
                        if (!bitFieldContainsModeBitmask(modesBitfield, gameMode)) {
                            if (bitFieldContainsModeBitmask(modesBitfield,
                                    GameManager.GAME_MODE_STANDARD)) {
                                // If the current set mode isn't supported, but we support STANDARD,
                                // then set the mode to STANDARD.
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
                    if (newGameMode != gameMode) {
                        setGameMode(packageName, newGameMode, userId);
                    }
                    updateCompatModeDownscale(packageName, gameMode);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to update compat modes for user: " + userId);
        }
    }

    private String[] getInstalledGamePackageNames(int userId) {
        final List<PackageInfo> packages =
                mPackageManager.getInstalledPackagesAsUser(0, userId);
        return packages.stream().filter(e -> e.applicationInfo != null && e.applicationInfo.category
                        == ApplicationInfo.CATEGORY_GAME)
                .map(e -> e.packageName)
                .toArray(String[]::new);
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public GamePackageConfiguration getConfig(String packageName) {
        return mConfigs.get(packageName);
    }

    private void registerPackageReceiver() {
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(ACTION_PACKAGE_ADDED);
        packageFilter.addAction(ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
                final Uri data = intent.getData();
                try {
                    final String packageName = data.getSchemeSpecificPart();
                    switch (intent.getAction()) {
                        case ACTION_PACKAGE_ADDED:
                        case ACTION_PACKAGE_CHANGED:
                            updateConfigsForUser(mContext.getUserId(), packageName);
                            break;
                        case ACTION_PACKAGE_REMOVED:
                            disableCompatScale(packageName);
                            synchronized (mDeviceConfigLock) {
                                mConfigs.remove(packageName);
                            }
                            break;
                        default:
                            // do nothing
                            break;
                    }
                } catch (NullPointerException e) {
                    Slog.e(TAG, "Failed to get package name for new package", e);
                }
            }
        };
        mContext.registerReceiver(packageReceiver, packageFilter);
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

    private static ServiceThread createServiceThread() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }
}
