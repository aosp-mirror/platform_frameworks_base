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
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The GameManager allows system apps to modify and query the game mode of apps.
 */
@SystemService(Context.GAME_SERVICE)
public final class GameManager {

    private static final String TAG = "GameManager";

    private final @Nullable Context mContext;
    private final IGameManagerService mService;

    /** @hide */
    @IntDef(flag = false, prefix = {"GAME_MODE_"}, value = {
            GAME_MODE_UNSUPPORTED, // 0
            GAME_MODE_STANDARD, // 1
            GAME_MODE_PERFORMANCE, // 2
            GAME_MODE_BATTERY, // 3
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

    GameManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = IGameManagerService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.GAME_SERVICE));
    }

    /**
     * Return the user selected game mode for this application.
     * <p>
     * An application can use <code>android:isGame="true"</code> or
     * <code>android:appCategory="game"</code> to indicate that the application is a game. If an
     * application is not a game, always return {@link #GAME_MODE_UNSUPPORTED}.
     * <p>
     * Developers should call this API every time the application is resumed.
     */
    public @GameMode int getGameMode() {
        try {
            return mService.getGameMode(mContext.getPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the game mode for the given package.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int getGameMode(@NonNull String packageName) {
        try {
            return mService.getGameMode(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the game mode for the given package.
     * <p>
     * The caller must have {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameMode(@NonNull String packageName, @GameMode int gameMode) {
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
        try {
            return mService.getAvailableGameModes(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
