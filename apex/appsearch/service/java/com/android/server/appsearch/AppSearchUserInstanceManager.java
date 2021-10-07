/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.appsearch;

import android.annotation.NonNull;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.stats.PlatformLogger;
import com.android.server.appsearch.visibilitystore.VisibilityStoreImpl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of AppSearch classes that should only be initialized once per device-user
 * and make up the core of the AppSearch system.
 *
 * @hide
 */
public final class AppSearchUserInstanceManager {
    private static final String TAG = "AppSearchUserInstanceMa";

    private static volatile AppSearchUserInstanceManager sAppSearchUserInstanceManager;

    @GuardedBy("mInstancesLocked")
    private final Map<UserHandle, AppSearchUserInstance> mInstancesLocked = new ArrayMap<>();

    private AppSearchUserInstanceManager() {}

    /**
     * Gets an instance of AppSearchUserInstanceManager to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static AppSearchUserInstanceManager getInstance() {
        if (sAppSearchUserInstanceManager == null) {
            synchronized (AppSearchUserInstanceManager.class) {
                if (sAppSearchUserInstanceManager == null) {
                    sAppSearchUserInstanceManager = new AppSearchUserInstanceManager();
                }
            }
        }
        return sAppSearchUserInstanceManager;
    }

    /**
     * Returns AppSearch directory in the credential encrypted system directory for the given user.
     *
     * <p>This folder should only be accessed after unlock.
     */
    public static File getAppSearchDir(@NonNull UserHandle userHandle) {
        // Duplicates the implementation of Environment#getDataSystemCeDirectory
        // TODO(b/191059409): Unhide Environment#getDataSystemCeDirectory and switch to it.
        File systemCeDir = new File(Environment.getDataDirectory(), "system_ce");
        File systemCeUserDir = new File(systemCeDir, String.valueOf(userHandle.getIdentifier()));
        return new File(systemCeUserDir, "appsearch");
    }

    /**
     * Gets an instance of AppSearchUserInstance for the given user, or creates one if none exists.
     *
     * <p>If no AppSearchUserInstance exists for the unlocked user, Icing will be initialized and
     * one will be created.
     *
     * @param userContext Context of the user calling AppSearch
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @param config Flag manager for AppSearch
     * @return An initialized {@link AppSearchUserInstance} for this user
     */
    @NonNull
    public AppSearchUserInstance getOrCreateUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull AppSearchConfig config)
            throws AppSearchException {
        Objects.requireNonNull(userContext);
        Objects.requireNonNull(userHandle);
        Objects.requireNonNull(config);

        synchronized (mInstancesLocked) {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                instance = createUserInstance(userContext, userHandle, config);
                mInstancesLocked.put(userHandle, instance);
            }
            return instance;
        }
    }

    /**
     * Closes and removes an {@link AppSearchUserInstance} for the given user.
     *
     * <p>All mutations applied to the underlying {@link AppSearchImpl} will be persisted to disk.
     *
     * @param userHandle The multi-user user handle of the user that need to be removed.
     */
    public void closeAndRemoveUserInstance(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppSearchUserInstance instance = mInstancesLocked.remove(userHandle);
            if (instance != null) {
                instance.getAppSearchImpl().close();
            }
        }
    }

    /**
     * Gets an {@link AppSearchUserInstance} for the given user.
     *
     * <p>This method should only be called by an initialized SearchSession, which has already
     * called {@link #getOrCreateUserInstance} before.
     *
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link AppSearchUserInstance} for this user
     * @throws IllegalStateException if {@link AppSearchUserInstance} haven't created for the given
     *                               user.
     */
    @NonNull
    public AppSearchUserInstance getUserInstance(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppSearchUserInstance instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
                throw new IllegalStateException(
                        "AppSearchUserInstance has never been created for: " + userHandle);
            }
            return instance;
        }
    }

    /**
     * Returns the list of all {@link UserHandle}s.
     *
     * <p>It can return an empty list if there is no {@link AppSearchUserInstance} created yet.
     */
    @NonNull
    public List<UserHandle> getAllUserHandles() {
        synchronized (mInstancesLocked) {
            return new ArrayList<>(mInstancesLocked.keySet());
        }
    }

    @NonNull
    private AppSearchUserInstance createUserInstance(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull AppSearchConfig config)
            throws AppSearchException {
        long totalLatencyStartMillis = SystemClock.elapsedRealtime();
        InitializeStats.Builder initStatsBuilder = new InitializeStats.Builder();

        // Initialize the classes that make up AppSearchUserInstance
        PlatformLogger logger = new PlatformLogger(userContext, config);

        File appSearchDir = getAppSearchDir(userHandle);
        File icingDir = new File(appSearchDir, "icing");
        Log.i(TAG, "Creating new AppSearch instance at: " + icingDir);
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                icingDir,
                new FrameworkLimitConfig(config),
                initStatsBuilder,
                new FrameworkOptimizeStrategy(config));

        long prepareVisibilityStoreLatencyStartMillis = SystemClock.elapsedRealtime();
        VisibilityStoreImpl visibilityStore =
                VisibilityStoreImpl.create(appSearchImpl, userContext);
        long prepareVisibilityStoreLatencyEndMillis = SystemClock.elapsedRealtime();

        initStatsBuilder
                .setTotalLatencyMillis(
                        (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                .setPrepareVisibilityStoreLatencyMillis(
                        (int)
                                (prepareVisibilityStoreLatencyEndMillis
                                        - prepareVisibilityStoreLatencyStartMillis));
        logger.logStats(initStatsBuilder.build());

        return new AppSearchUserInstance(logger, appSearchImpl, visibilityStore);
    }
}
