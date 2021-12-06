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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.service.games.GameService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.List;

final class GameServiceController {
    private static final String TAG = "GameServiceController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    @Nullable
    private SystemService.TargetUser mCurrentForegroundUser;
    private boolean mHasBootCompleted;

    @Nullable
    private GameServiceConnection mGameServiceConnection;

    GameServiceController(Context context) {
        mContext = context;
    }

    void onBootComplete() {
        mHasBootCompleted = true;

        evaluateGameServiceConnection();
    }

    void notifyUserStarted(@NonNull SystemService.TargetUser user) {
        if (mCurrentForegroundUser != null) {
            return;
        }

        mCurrentForegroundUser = user;
        evaluateGameServiceConnection();
    }

    void notifyNewForegroundUser(@NonNull SystemService.TargetUser user) {
        mCurrentForegroundUser = user;
        evaluateGameServiceConnection();
    }

    void notifyUserStopped(@NonNull SystemService.TargetUser user) {
        if (mCurrentForegroundUser == null
                || mCurrentForegroundUser.getUserIdentifier() != user.getUserIdentifier()) {
            return;
        }

        mCurrentForegroundUser = null;
        evaluateGameServiceConnection();
    }

    private void evaluateGameServiceConnection() {
        if (!mHasBootCompleted) {
            return;
        }

        // TODO(b/204565942): Only shutdown the existing service connection if the game service
        // provider or user has changed.
        if (mGameServiceConnection != null) {
            mGameServiceConnection.disconnect();
            mGameServiceConnection = null;
        }

        boolean isUserSupported =
                mCurrentForegroundUser != null
                        && mCurrentForegroundUser.isFull()
                        && !mCurrentForegroundUser.isManagedProfile();
        if (!isUserSupported) {
            if (DEBUG && mCurrentForegroundUser != null) {
                Slog.d(TAG, "User not supported: " + mCurrentForegroundUser);
            }
            return;
        }

        ComponentName gameServiceComponentName =
                determineGameServiceComponentName(mCurrentForegroundUser.getUserIdentifier());
        if (gameServiceComponentName == null) {
            return;
        }

        mGameServiceConnection = new GameServiceConnection(
                mContext,
                gameServiceComponentName,
                mCurrentForegroundUser.getUserIdentifier());
        mGameServiceConnection.connect();
    }

    @Nullable
    private ComponentName determineGameServiceComponentName(int userId) {
        String gameServicePackage =
                mContext.getResources().getString(
                        com.android.internal.R.string.config_systemGameService);
        if (TextUtils.isEmpty(gameServicePackage)) {
            if (DEBUG) {
                Slog.d(TAG, "No game service package defined");
            }
            return null;
        }

        List<ResolveInfo> gameServiceResolveInfos =
                mContext.getPackageManager().queryIntentServicesAsUser(
                        new Intent(GameService.SERVICE_INTERFACE).setPackage(gameServicePackage),
                        PackageManager.MATCH_SYSTEM_ONLY,
                        userId);

        if (gameServiceResolveInfos.isEmpty()) {
            Slog.v(TAG, "No available game service found for user id: " + userId);
            return null;
        }

        for (ResolveInfo resolveInfo : gameServiceResolveInfos) {
            if (resolveInfo.serviceInfo == null) {
                continue;
            }
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (!serviceInfo.isEnabled()) {
                continue;
            }
            return serviceInfo.getComponentName();
        }

        Slog.v(TAG, "No game service found for user id: " + userId);
        return null;
    }
}
