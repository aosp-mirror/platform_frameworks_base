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

package com.android.server.appsearch.stats;

import android.annotation.NonNull;
import android.content.Context;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.AppSearchManagerService;

import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of instances of {@link PlatformLogger}.
 *
 * <p>These instances are managed per unique device-user.
 */
public final class LoggerInstanceManager {
    // TODO(b/173532925) flags to control those three
    // So probably we can't pass those three in the constructor but need to fetch the latest value
    // every time we need them in the logger.
    private static final int MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS = 100;
    private static final int DEFAULT_SAMPLING_RATIO = 10;

    private static volatile LoggerInstanceManager sLoggerInstanceManager;

    @GuardedBy("mInstancesLocked")
    private final Map<UserHandle, PlatformLogger> mInstancesLocked = new ArrayMap<>();

    private LoggerInstanceManager() {
    }

    /**
     * Gets an instance of {@link LoggerInstanceManager} to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static LoggerInstanceManager getInstance() {
        if (sLoggerInstanceManager == null) {
            synchronized (LoggerInstanceManager.class) {
                if (sLoggerInstanceManager == null) {
                    sLoggerInstanceManager =
                            new LoggerInstanceManager();
                }
            }
        }
        return sLoggerInstanceManager;
    }

    /**
     * Gets an instance of PlatformLogger for the given user, or creates one if none exists.
     *
     * @param context The context
     * @param userHandle  The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link PlatformLogger} for this user
     */
    @NonNull
    public PlatformLogger getOrCreatePlatformLogger(
            @NonNull Context context, @NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            PlatformLogger instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                instance = new PlatformLogger(context, userHandle, new PlatformLogger.Config(
                        MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        DEFAULT_SAMPLING_RATIO,
                        // TODO(b/173532925) re-enable sampling ratios for different stats types
                        // once we have P/H flag manager setup in ag/13977824
                        /*samplingRatios=*/ new SparseIntArray()));
                mInstancesLocked.put(userHandle, instance);
            }
            return instance;
        }
    }

    /**
     * Gets an instance of PlatformLogger for the given user.
     *
     * <p>This method should only be called by an initialized SearchSession, which has been already
     * created the PlatformLogger instance for the given user.
     *
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link PlatformLogger} for this user
     * @throws IllegalStateException if {@link PlatformLogger} haven't created for the given user.
     */
    @NonNull
    public PlatformLogger getPlatformLogger(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            PlatformLogger instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
                throw new IllegalStateException(
                        "PlatformLogger has never been created for: " + userHandle);
            }
            return instance;
        }
    }

    /**
     * Remove an instance of {@link PlatformLogger} for the given user.
     *
     * <p>This method should only be called if {@link AppSearchManagerService} receives an
     * ACTION_USER_REMOVED, which the logger instance of given user should be removed.
     *
     * @param userHandle The multi-user handle of the user that need to be removed.
     */
    public void removePlatformLoggerForUser(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            mInstancesLocked.remove(userHandle);
        }
    }
}
