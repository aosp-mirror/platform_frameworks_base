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
import android.compat.Compatibility;
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
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
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
    static final int WRITE_SETTINGS_DELAY = 10 * 1000;  // 10 seconds

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
                    removeMessages(POPULATE_GAME_MODE_SETTINGS, msg.obj);
                    loadDeviceConfigLocked();
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
            synchronized (mDeviceConfigLock) {
                for (String key : properties.getKeyset()) {
                    try {
                        // Check if the package is installed before caching it.
                        final String packageName = keyToPackageName(key);
                        mPackageManager.getPackageInfo(packageName, 0);
                        final GamePackageConfiguration config =
                                GamePackageConfiguration.fromProperties(key, properties);
                        if (config.isValid()) {
                            putConfig(config);
                        } else {
                            // This means that we received a bad config, or the config was deleted.
                            Slog.i(TAG, "Removing config for: " + packageName);
                            mConfigs.remove(packageName);
                            disableCompatScale(packageName);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        if (DEBUG) {
                            Slog.v(TAG, "Package name not found", e);
                        }
                    }
                }
            }
        }

        @Override
        public void finalize() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }
    }

    private static class GameModeConfiguration {
        public static final String TAG = "GameManagerService_GameModeConfiguration";
        public static final String MODE_KEY = "mode";
        public static final String SCALING_KEY = "downscaleFactor";

        private final @GameMode int mGameMode;
        private final String mScaling;

        private GameModeConfiguration(@NonNull int gameMode,
                @NonNull String scaling) {
            mGameMode = gameMode;
            mScaling = scaling;
        }

        public static GameModeConfiguration fromKeyValueListParser(KeyValueListParser parser) {
            return new GameModeConfiguration(
                    parser.getInt(MODE_KEY, GameManager.GAME_MODE_UNSUPPORTED),
                    parser.getString(SCALING_KEY, "1.0")
            );
        }

        public int getGameMode() {
            return mGameMode;
        }

        public String getScaling() {
            return mScaling;
        }

        public boolean isValid() {
            return (mGameMode == GameManager.GAME_MODE_PERFORMANCE
                    || mGameMode == GameManager.GAME_MODE_BATTERY) && getCompatChangeId() != 0;
        }

        public String toString() {
            return "[Game Mode:" + mGameMode + ",Scaling:" + mScaling + "]";
        }

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

    private static class GamePackageConfiguration {
        public static final String TAG = "GameManagerService_GamePackageConfiguration";

        private final String mPackageName;
        private final ArrayMap<Integer, GameModeConfiguration> mModeConfigs;

        private GamePackageConfiguration(String keyName) {
            mPackageName = keyToPackageName(keyName);
            mModeConfigs = new ArrayMap<>();
        }

        public String getPackageName() {
            return mPackageName;
        }

        public @GameMode int[] getAvailableGameModes() {
            if (mModeConfigs.keySet().size() > 0) {
                return mModeConfigs.keySet().stream()
                            .mapToInt(Integer::intValue).toArray();
            }
            return new int[]{GameManager.GAME_MODE_UNSUPPORTED};
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

        /**
         * Create a new instance from a package name and DeviceConfig.Properties instance
         */
        public static GamePackageConfiguration fromProperties(String key,
                Properties properties) {
            final GamePackageConfiguration packageConfig = new GamePackageConfiguration(key);
            final String configString = properties.getString(key, "");
            final String[] gameModeConfigStrings = configString.split(":");
            for (String gameModeConfigString : gameModeConfigStrings) {
                try {
                    final KeyValueListParser parser = new KeyValueListParser(',');
                    parser.setString(gameModeConfigString);
                    final GameModeConfiguration config =
                            GameModeConfiguration.fromKeyValueListParser(parser);
                    packageConfig.addModeConfig(config);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Invalid config string");
                }
            }
            return packageConfig;
        }

        public boolean isValid() {
            return mModeConfigs.size() > 0;
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
        final Message msg = mHandler.obtainMessage(POPULATE_GAME_MODE_SETTINGS);
        mHandler.sendMessage(msg);
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

    private void loadDeviceConfigLocked() {
        final List<PackageInfo> packages = mPackageManager.getInstalledPackages(0);
        final String[] packageNames = packages.stream().map(e -> packageNameToKey(e.packageName))
                .toArray(String[]::new);
        synchronized (mDeviceConfigLock) {
            final Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_GAME_OVERLAY, packageNames);
            for (String key : properties.getKeyset()) {
                final GamePackageConfiguration config =
                        GamePackageConfiguration.fromProperties(key, properties);
                putConfig(config);
            }
        }
    }

    private void disableCompatScale(String packageName) {
        final long uid = Binder.clearCallingIdentity();
        try {
            final HashSet<Long> disabledSet = new HashSet<>();
            disabledSet.add(DOWNSCALED);
            final CompatibilityChangeConfig changeConfig = new CompatibilityChangeConfig(
                    new Compatibility.ChangeConfig(new HashSet<>(), disabledSet));
            // TODO: switch to new API provided by aosp/1599153 once merged
            try {
                mPlatformCompat.setOverridesForTest(changeConfig, packageName);
            } catch (SecurityException e) {
                Slog.e(TAG, "Missing compat override permission", e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#setOverridesForTest", e);
            }
        } finally {
            Binder.restoreCallingIdentity(uid);
        }
    }

    private void enableCompatScale(String packageName, long scaleId) {
        final long uid = Binder.clearCallingIdentity();
        try {
            final HashSet<Long> disabledSet = new HashSet<>();
            final HashSet<Long> enabledSet = new HashSet<>();
            disabledSet.add(DOWNSCALE_50);
            disabledSet.add(DOWNSCALE_60);
            disabledSet.add(DOWNSCALE_70);
            disabledSet.add(DOWNSCALE_80);
            disabledSet.add(DOWNSCALE_90);
            disabledSet.remove(scaleId);
            enabledSet.add(DOWNSCALED);
            enabledSet.add(scaleId);
            final CompatibilityChangeConfig changeConfig = new CompatibilityChangeConfig(
                    new Compatibility.ChangeConfig(enabledSet, disabledSet));
            // TODO: switch to new API provided by aosp/1599153 once merged
            try {
                mPlatformCompat.setOverridesForTest(changeConfig, packageName);
            } catch (SecurityException e) {
                Slog.e(TAG, "Missing compat override permission", e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to call IPlatformCompat#setOverridesForTest", e);
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
                Slog.v(TAG, "Disabling downscale");
                return;
            }
            if (DEBUG) {
                Slog.v(TAG, dumpDeviceConfigs());
            }
            final GamePackageConfiguration packageConfig = mConfigs.get(packageName);
            if (packageConfig == null) {
                Slog.w(TAG, "Package configuration not found for " + packageName);
                return;
            }
            final GameModeConfiguration modeConfig = packageConfig.getGameModeConfiguration(
                    gameMode);
            if (modeConfig == null) {
                Slog.w(TAG, "Game mode " + gameMode + " not found for " + packageName);
                return;
            }
            long scaleId = modeConfig.getCompatChangeId();
            if (scaleId == 0) {
                Slog.w(TAG, "Invalid downscaling change id " + scaleId + " for "
                        + packageName);
                return;
            }
            Slog.i(TAG, "Enabling downscale: " + scaleId + " for " + packageName);
            enableCompatScale(packageName, scaleId);
        }
    }

    private void putConfig(GamePackageConfiguration config) {
        if (config.isValid()) {
            if (DEBUG) {
                Slog.i(TAG, "Adding config: " + config.toString());
            }
            mConfigs.put(config.getPackageName(), config);
        } else {
            Slog.w(TAG, "Invalid package config for "
                    + config.getPackageName() + ":" + config.toString());
        }
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
                            synchronized (mDeviceConfigLock) {
                                Properties properties = DeviceConfig.getProperties(
                                        DeviceConfig.NAMESPACE_GAME_OVERLAY,
                                        packageNameToKey(packageName));
                                for (String key : properties.getKeyset()) {
                                    GamePackageConfiguration config =
                                            GamePackageConfiguration.fromProperties(key,
                                                    properties);
                                    putConfig(config);
                                }
                            }
                            break;
                        case ACTION_PACKAGE_REMOVED:
                            disableCompatScale(packageName);
                            mConfigs.remove(packageName);
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

    /**
     * Valid package name characters are [a-zA-Z0-9_] with a '.' delimiter. Policy keys can only use
     * [a-zA-Z0-9_] so we must handle periods. We do this by appending a '_' to any existing
     * sequence of '_', then we replace all '.' chars with '_';
     */
    private static String packageNameToKey(String name) {
        return name.replaceAll("(_+)", "_$1").replaceAll("\\.", "_");
    }

    /**
     * Replace the last '_' in a sequence with '.' (this can be one or more chars), then replace the
     * resulting special case '_.' with just '_' to get the original package name.
     */
    private static String keyToPackageName(String key) {
        return key.replaceAll("(_)(?!\\1)", ".").replaceAll("_\\.", "_");
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
