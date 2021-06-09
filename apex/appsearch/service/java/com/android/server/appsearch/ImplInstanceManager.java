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
import android.annotation.Nullable;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.os.Environment;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.AppSearchLogger;
import com.android.server.appsearch.external.localstorage.FrameworkOptimizeStrategy;

import java.io.File;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of instances of {@link AppSearchImpl}.
 *
 * <p>These instances are managed per unique device-user.
 */
public final class ImplInstanceManager {
    private static final String APP_SEARCH_DIR = "appSearch";

    private static ImplInstanceManager sImplInstanceManager;

    @GuardedBy("mInstancesLocked")
    private final Map<UserHandle, AppSearchImpl> mInstancesLocked = new ArrayMap<>();

    /**
     * Gets an instance of ImplInstanceManager to be used.
     *
     * <p>If no instance has been initialized yet, a new one will be created. Otherwise, the
     * existing instance will be returned.
     */
    @NonNull
    public static ImplInstanceManager getInstance(@NonNull Context context) {
        if (sImplInstanceManager == null) {
            synchronized (ImplInstanceManager.class) {
                if (sImplInstanceManager == null) {
                    sImplInstanceManager = new ImplInstanceManager();
                }
            }
        }
        return sImplInstanceManager;
    }

    /**
     * Returns AppSearch directory in the credential encrypted system directory for the given user.
     *
     * <p>This folder should only be accessed after unlock.
     */
    public static File getAppSearchDir(@NonNull UserHandle userHandle) {
        return new File(
                Environment.getDataSystemCeDirectory(userHandle.getIdentifier()), APP_SEARCH_DIR);
    }

    /**
     * Gets an instance of AppSearchImpl for the given user, or creates one if none exists.
     *
     * <p>If no AppSearchImpl instance exists for the unlocked user, Icing will be initialized and
     * one will be created.
     *
     * @param context The system context
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link AppSearchImpl} for this user
     */
    @NonNull
    public AppSearchImpl getOrCreateAppSearchImpl(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @Nullable AppSearchLogger logger) throws AppSearchException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(userHandle);

        synchronized (mInstancesLocked) {
            AppSearchImpl instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                Context userContext = context.createContextAsUser(userHandle, /*flags=*/ 0);
                instance = createImpl(userContext, userHandle, logger);
                mInstancesLocked.put(userHandle, instance);
            }
            return instance;
        }
    }

    /**
     * Remove an instance of {@link AppSearchImpl} for the given user.
     *
     * <p>This method should only be called if {@link AppSearchManagerService} receives an
     * ACTION_USER_REMOVED, which the instance of given user should be removed.
     *
     * <p>If the user is removed, the "credential encrypted" system directory where icing lives will
     * be auto-deleted. So we shouldn't worry about persist data or close the AppSearchImpl.
     *
     * @param userHandle The multi-user user handle of the user that need to be removed.
     */
    public void removeAppSearchImplForUser(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            // no need to close and persist data to disk since we are removing them now.
            mInstancesLocked.remove(userHandle);
        }
    }

    /**
     * Close and remove an instance of {@link AppSearchImpl} for the given user.
     *
     * <p>All mutation apply to this {@link AppSearchImpl} will be persisted to disk.
     *
     * @param userHandle The multi-user user handle of the user that need to be removed.
     */
    public void closeAndRemoveAppSearchImplForUser(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppSearchImpl appSearchImpl = mInstancesLocked.get(userHandle);
            if (appSearchImpl != null) {
                appSearchImpl.close();
                mInstancesLocked.remove(userHandle);
            }
        }
    }

    /**
     * Gets an instance of AppSearchImpl for the given user.
     *
     * <p>This method should only be called by an initialized SearchSession, which has been already
     * created the AppSearchImpl instance for the given user.
     *
     * @param userHandle The multi-user handle of the device user calling AppSearch
     * @return An initialized {@link AppSearchImpl} for this user
     * @throws IllegalStateException if {@link AppSearchImpl} haven't created for the given user.
     */
    @NonNull
    public AppSearchImpl getAppSearchImpl(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        synchronized (mInstancesLocked) {
            AppSearchImpl instance = mInstancesLocked.get(userHandle);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
                throw new IllegalStateException(
                        "AppSearchImpl has never been created for: " + userHandle);
            }
            return instance;
        }
    }

    private AppSearchImpl createImpl(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        File appSearchDir = getAppSearchDir(userHandle);
        return AppSearchImpl.create(
                appSearchDir,
                userContext,
                /*logger=*/ null,
                new FrameworkOptimizeStrategy());
    }
}
