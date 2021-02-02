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

import android.annotation.IntDef;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The GameManager allows system apps to modify and query the game mode of apps.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
@SystemService(Context.GAME_SERVICE)
public final class GameManager {

    private static final String TAG = "GameManager";

    private final Context mContext;
    private final IGameManagerService mService;

    @IntDef(flag = false, prefix = { "GAME_MODE_" }, value = {
            GAME_MODE_UNSUPPORTED, // 0
            GAME_MODE_STANDARD, // 1
            GAME_MODE_PERFORMANCE, // 2
            GAME_MODE_BATTERY, // 3
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GameMode {}

    public static final int GAME_MODE_UNSUPPORTED = 0;
    public static final int GAME_MODE_STANDARD = 1;
    public static final int GAME_MODE_PERFORMANCE = 2;
    public static final int GAME_MODE_BATTERY = 3;

    public GameManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = IGameManagerService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.GAME_SERVICE));
    }

    @VisibleForTesting
    public GameManager(Context context, IGameManagerService gameManagerService) {
        mContext = context;
        mService = gameManagerService;
    }

    /**
     * Returns the game mode for the given package.
     */
    // TODO(b/178111358): Add @RequiresPermission.
    @UserHandleAware
    public @GameMode int getGameMode(String packageName) {
        try {
            return mService.getGameMode(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the game mode for the given package.
     */
    // TODO(b/178111358): Add @RequiresPermission.
    @UserHandleAware
    public void setGameMode(String packageName, @GameMode int gameMode) {
        try {
            mService.setGameMode(packageName, gameMode, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
