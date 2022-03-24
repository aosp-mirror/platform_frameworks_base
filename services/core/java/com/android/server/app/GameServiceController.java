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
import android.annotation.WorkerThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PatternMatcher;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.app.GameServiceConfiguration.GameServiceComponentConfiguration;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Responsible for managing the Game Service API.
 *
 * Key responsibilities selecting the active Game Service provider, binding to the Game Service
 * provider services, and driving the GameService/GameSession lifecycles.
 */
final class GameServiceController {
    private static final String TAG = "GameServiceController";

    private final Object mLock = new Object();
    private final Context mContext;
    private final Executor mBackgroundExecutor;
    private final GameServiceProviderSelector mGameServiceProviderSelector;
    private final GameServiceProviderInstanceFactory mGameServiceProviderInstanceFactory;

    private volatile boolean mHasBootCompleted;
    @Nullable
    private volatile String mGameServiceProviderOverride;
    @Nullable
    private BroadcastReceiver mGameServicePackageChangedReceiver;
    @Nullable
    private volatile SystemService.TargetUser mCurrentForegroundUser;
    @GuardedBy("mLock")
    @Nullable
    private volatile GameServiceComponentConfiguration mActiveGameServiceComponentConfiguration;
    @GuardedBy("mLock")
    @Nullable
    private volatile GameServiceProviderInstance mGameServiceProviderInstance;
    @GuardedBy("mLock")
    @Nullable
    private volatile String mActiveGameServiceProviderPackage;

    GameServiceController(
            @NonNull Context context, @NonNull Executor backgroundExecutor,
            @NonNull GameServiceProviderSelector gameServiceProviderSelector,
            @NonNull GameServiceProviderInstanceFactory gameServiceProviderInstanceFactory) {
        mContext = context;
        mGameServiceProviderInstanceFactory = gameServiceProviderInstanceFactory;
        mBackgroundExecutor = backgroundExecutor;
        mGameServiceProviderSelector = gameServiceProviderSelector;
    }

    void onBootComplete() {
        if (mHasBootCompleted) {
            return;
        }
        mHasBootCompleted = true;

        mBackgroundExecutor.execute(this::evaluateActiveGameServiceProvider);
    }

    void notifyUserStarted(@NonNull SystemService.TargetUser user) {
        if (mCurrentForegroundUser != null) {
            return;
        }

        setCurrentForegroundUserAndEvaluateProvider(user);
    }

    void notifyNewForegroundUser(@NonNull SystemService.TargetUser user) {
        setCurrentForegroundUserAndEvaluateProvider(user);
    }

    void notifyUserUnlocking(@NonNull SystemService.TargetUser user) {
        boolean isSameAsForegroundUser =
                mCurrentForegroundUser != null
                        && mCurrentForegroundUser.getUserIdentifier() == user.getUserIdentifier();
        if (!isSameAsForegroundUser) {
            return;
        }

        // It is likely that the Game Service provider's components are not Direct Boot mode aware
        // and will not be capable of running until the user has unlocked the device. To allow for
        // this we re-evaluate the active game service provider once these components are available.

        mBackgroundExecutor.execute(this::evaluateActiveGameServiceProvider);
    }

    void notifyUserStopped(@NonNull SystemService.TargetUser user) {
        boolean isSameAsForegroundUser =
                mCurrentForegroundUser != null
                        && mCurrentForegroundUser.getUserIdentifier() == user.getUserIdentifier();
        if (!isSameAsForegroundUser) {
            return;
        }

        setCurrentForegroundUserAndEvaluateProvider(null);
    }

    void setGameServiceProvider(@Nullable String packageName) {
        boolean hasPackageChanged = !Objects.equals(mGameServiceProviderOverride, packageName);
        if (!hasPackageChanged) {
            return;
        }
        mGameServiceProviderOverride = packageName;

        mBackgroundExecutor.execute(this::evaluateActiveGameServiceProvider);
    }

    private void setCurrentForegroundUserAndEvaluateProvider(
            @Nullable SystemService.TargetUser user) {
        boolean hasUserChanged =
                !Objects.equals(mCurrentForegroundUser, user);
        if (!hasUserChanged) {
            return;
        }
        mCurrentForegroundUser = user;

        mBackgroundExecutor.execute(this::evaluateActiveGameServiceProvider);
    }

    @WorkerThread
    private void evaluateActiveGameServiceProvider() {
        if (!mHasBootCompleted) {
            return;
        }

        synchronized (mLock) {
            final GameServiceConfiguration selectedGameServiceConfiguration =
                    mGameServiceProviderSelector.get(mCurrentForegroundUser,
                            mGameServiceProviderOverride);
            final String gameServicePackage =
                    selectedGameServiceConfiguration == null ? null :
                            selectedGameServiceConfiguration.getPackageName();
            final GameServiceComponentConfiguration gameServiceComponentConfiguration =
                    selectedGameServiceConfiguration == null ? null
                            : selectedGameServiceConfiguration
                                    .getGameServiceComponentConfiguration();

            evaluateGameServiceProviderPackageChangedListenerLocked(gameServicePackage);

            boolean didActiveGameServiceProviderChange =
                    !Objects.equals(gameServiceComponentConfiguration,
                            mActiveGameServiceComponentConfiguration);
            if (!didActiveGameServiceProviderChange) {
                return;
            }

            if (mGameServiceProviderInstance != null) {
                Slog.i(TAG, "Stopping Game Service provider: "
                        + mActiveGameServiceComponentConfiguration);
                mGameServiceProviderInstance.stop();
                mGameServiceProviderInstance = null;
            }

            mActiveGameServiceComponentConfiguration = gameServiceComponentConfiguration;
            if (mActiveGameServiceComponentConfiguration == null) {
                return;
            }

            Slog.i(TAG,
                    "Starting Game Service provider: " + mActiveGameServiceComponentConfiguration);
            mGameServiceProviderInstance =
                    mGameServiceProviderInstanceFactory.create(
                            mActiveGameServiceComponentConfiguration);
            mGameServiceProviderInstance.start();
        }
    }

    @GuardedBy("mLock")
    private void evaluateGameServiceProviderPackageChangedListenerLocked(
            @Nullable String gameServicePackage) {
        if (TextUtils.equals(mActiveGameServiceProviderPackage, gameServicePackage)) {
            return;
        }

        if (mGameServicePackageChangedReceiver != null) {
            mContext.unregisterReceiver(mGameServicePackageChangedReceiver);
            mGameServicePackageChangedReceiver = null;
        }

        mActiveGameServiceProviderPackage = gameServicePackage;

        if (TextUtils.isEmpty(mActiveGameServiceProviderPackage)) {
            return;
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        intentFilter.addDataSchemeSpecificPart(gameServicePackage, PatternMatcher.PATTERN_LITERAL);
        mGameServicePackageChangedReceiver = new PackageChangedBroadcastReceiver(
                gameServicePackage);
        mContext.registerReceiver(
                mGameServicePackageChangedReceiver,
                intentFilter);
    }

    private final class PackageChangedBroadcastReceiver extends BroadcastReceiver {
        private final String mPackageName;

        PackageChangedBroadcastReceiver(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TextUtils.equals(intent.getData().getSchemeSpecificPart(), mPackageName)) {
                return;
            }
            mBackgroundExecutor.execute(
                    GameServiceController.this::evaluateActiveGameServiceProvider);
        }
    }
}
