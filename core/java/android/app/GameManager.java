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

package android.app;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The GameManager allows system apps to modify and query the game mode of apps.
 *
 * <p><b>Note:</b> After {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, some devices
 * that do not support the GameManager features <i>may</i> not publish a GameManager instance.
 * These device types include:
 * <ul>
 *      <li> Wear devices ({@link PackageManager#FEATURE_WATCH})
 * </ul>
 *
 * <p>Therefore, you should always do a {@code null} check on the return value of
 * {@link Context#getSystemService(Class)} and {@link Context#getSystemService(String)} when trying
 * to obtain an instance of GameManager on the aforementioned device types.
 */
@SystemService(Context.GAME_SERVICE)
public final class GameManager {

    private static final String TAG = "GameManager";

    private final @Nullable Context mContext;
    private final @Nullable IGameManagerService mService;

    /** @hide */
    @IntDef(flag = false, prefix = {"GAME_MODE_"}, value = {
            GAME_MODE_UNSUPPORTED, // 0
            GAME_MODE_STANDARD, // 1
            GAME_MODE_PERFORMANCE, // 2
            GAME_MODE_BATTERY, // 3
            GAME_MODE_CUSTOM, // 4
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GameMode {
    }

    /**
     * Game mode is not supported for this application.
     */
    public static final int GAME_MODE_UNSUPPORTED = 0;

    /**
     * Standard game mode means the platform will use the game's default
     * performance characteristics.
     */
    public static final int GAME_MODE_STANDARD = 1;

    /**
     * Performance game mode maximizes the game's performance.
     * <p>
     * This game mode is highly likely to increase battery consumption.
     */
    public static final int GAME_MODE_PERFORMANCE = 2;

    /**
     * Battery game mode will save battery and give longer game play time.
     */
    public static final int GAME_MODE_BATTERY = 3;

    /**
     * Custom game mode that has user-provided configuration overrides.
     * <p>
     * Custom game mode is expected to be handled only by the platform using users'
     * preferred config. It is currently not allowed to opt in custom mode in game mode XML file nor
     * expected to perform app-based optimizations when activated.
     */
    public static final int GAME_MODE_CUSTOM = 4;

    GameManager(Context context, @Nullable IGameManagerService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Return the user selected game mode for this application.
     * <p>
     * An application can use <code>android:isGame="true"</code> or
     * <code>android:appCategory="game"</code> to indicate that the application is a game. If an
     * application is not a game, always return {@link #GAME_MODE_UNSUPPORTED}.
     * <p>
     * Developers should call this API every time the application is resumed.
     * <p>
     * If a game's <code>targetSdkVersion</code> is {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * or lower, and when the game mode is set to {@link #GAME_MODE_CUSTOM} which is available in
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or newer, this API will return
     * {@link #GAME_MODE_STANDARD} instead for backward compatibility.
     */
    public @GameMode int getGameMode() {
        return getGameModeImpl(mContext.getPackageName(),
                mContext.getApplicationInfo().targetSdkVersion);
    }

    /**
     * Gets the game mode for the given package.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     * <p>
     * Also see {@link #getGameMode()} on how it handles SDK version compatibility.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int getGameMode(@NonNull String packageName) {
        final int targetSdkVersion;
        try {
            final ApplicationInfo applicationInfo = mContext.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
            targetSdkVersion = applicationInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ex) {
            return GAME_MODE_UNSUPPORTED;
        }
        return getGameModeImpl(packageName, targetSdkVersion);
    }

    // This target SDK version check can be performed against any game by a privileged app, and
    // we don't want a binder call each time to check on behalf of an app using CompatChange.
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private @GameMode int getGameModeImpl(@NonNull String packageName, int targetSdkVersion) {
        if (mService == null) return GAME_MODE_UNSUPPORTED;
        final int gameMode;
        try {
            gameMode = mService.getGameMode(packageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (gameMode == GAME_MODE_CUSTOM && targetSdkVersion <= Build.VERSION_CODES.TIRAMISU) {
            return GAME_MODE_STANDARD;
        }
        return gameMode;
    }

    /**
     * Returns the {@link GameModeInfo} associated with the game associated with
     * the given {@code packageName}. If the given package is not a game, {@code null} is
     * always returned.
     * <p>
     * An application can use <code>android:isGame="true"</code> or
     * <code>android:appCategory="game"</code> to indicate that the application is a game.
     * If the manifest doesn't define a category, the category can also be
     * provided by the installer via
     * {@link android.content.pm.PackageManager#setApplicationCategoryHint(String, int)}.
     * <p>
     *
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @Nullable GameModeInfo getGameModeInfo(@NonNull String packageName) {
        if (mService == null) return null;
        try {
            return mService.getGameModeInfo(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the game mode for the given package.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     * <p>
     * Setting the game mode on a non-game application or setting a game to
     * {@link #GAME_MODE_UNSUPPORTED} will have no effect.
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameMode(@NonNull String packageName, @GameMode int gameMode) {
        if (mService == null) return;
        try {
            mService.setGameMode(packageName, gameMode, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of supported game modes for a given package.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int[] getAvailableGameModes(@NonNull String packageName) {
        if (mService == null) return new int[0];
        try {
            return mService.getAvailableGameModes(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns if ANGLE is enabled for a given package and user ID.
     * <p>
     * ANGLE (Almost Native Graphics Layer Engine) can translate OpenGL ES commands to Vulkan
     * commands. Enabling ANGLE may improve the performance and/or reduce the power consumption of
     * applications.
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public boolean isAngleEnabled(@NonNull String packageName) {
        if (mService == null) return false;
        try {
            return mService.isAngleEnabled(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set up the automatic power boost if appropriate.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void notifyGraphicsEnvironmentSetup() {
        if (mService == null) return;
        try {
            mService.notifyGraphicsEnvironmentSetup(
                    mContext.getPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by games to communicate the current state to the platform.
     * @param gameState An object set to the current state.
     */
    public void setGameState(@NonNull GameState gameState) {
        if (mService == null) return;
        try {
            mService.setGameState(mContext.getPackageName(), gameState, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Sets the game service provider to the given package name for test only.
     *
     * <p>Passing in {@code null} will clear a previously set value.
     * @hide
     */
    @TestApi
    public void setGameServiceProvider(@Nullable String packageName) {
        if (mService == null) return;
        try {
            mService.setGameServiceProvider(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the config for the game's {@link #GAME_MODE_CUSTOM} mode.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     *
     * @param packageName The package name of the game to update
     * @param gameModeConfig The configuration to use for game mode interventions
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void updateCustomGameModeConfiguration(@NonNull String packageName,
            @NonNull GameModeConfiguration gameModeConfig) {
        if (mService == null) return;
        try {
            mService.updateCustomGameModeConfiguration(packageName, gameModeConfig,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
