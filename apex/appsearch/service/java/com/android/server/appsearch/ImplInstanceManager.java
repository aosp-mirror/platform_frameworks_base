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

import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.AppSearchLogger;

import java.io.File;

/**
 * Manages the lifecycle of instances of {@link AppSearchImpl}.
 *
 * <p>These instances are managed per unique device-user.
 */
public final class ImplInstanceManager {
    private static final String APP_SEARCH_DIR = "appSearch";

    private static ImplInstanceManager sImplInstanceManager;

    @GuardedBy("mInstancesLocked")
    private final SparseArray<AppSearchImpl> mInstancesLocked = new SparseArray<>();

    private final String mGlobalQuerierPackage;

    private ImplInstanceManager(@NonNull String globalQuerierPackage) {
        mGlobalQuerierPackage = globalQuerierPackage;
    }

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
                    sImplInstanceManager =
                            new ImplInstanceManager(
                                    getGlobalAppSearchDataQuerierPackageName(context));
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
    public static File getAppSearchDir(@UserIdInt int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), APP_SEARCH_DIR);
    }

    /**
     * Gets an instance of AppSearchImpl for the given user, or creates one if none exists.
     *
     * <p>If no AppSearchImpl instance exists for the unlocked user, Icing will be initialized and
     * one will be created.
     *
     * @param context The context
     * @param userId  The multi-user userId of the device user calling AppSearch
     * @return An initialized {@link AppSearchImpl} for this user
     */
    @NonNull
    public AppSearchImpl getOrCreateAppSearchImpl(
            @NonNull Context context, @UserIdInt int userId, @Nullable AppSearchLogger logger)
            throws AppSearchException {
        synchronized (mInstancesLocked) {
            AppSearchImpl instance = mInstancesLocked.get(userId);
            if (instance == null) {
                instance = createImpl(context, userId, logger);
                mInstancesLocked.put(userId, instance);
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
     * @param userId The multi-user userId of the user that need to be removed.
     */
    public void removeAppSearchImplForUser(@UserIdInt int userId) {
        synchronized (mInstancesLocked) {
            // no need to close and persist data to disk since we are removing them now.
            mInstancesLocked.remove(userId);
        }
    }

    /**
     * Close and remove an instance of {@link AppSearchImpl} for the given user.
     *
     * <p>All mutation apply to this {@link AppSearchImpl} will be persisted to disk.
     *
     * @param userId The multi-user userId of the user that need to be removed.
     */
    public void closeAndRemoveAppSearchImplForUser(@UserIdInt int userId) {
        synchronized (mInstancesLocked) {
            AppSearchImpl appSearchImpl = mInstancesLocked.get(userId);
            if (appSearchImpl != null) {
                appSearchImpl.close();
                mInstancesLocked.remove(userId);
            }
        }
    }

    /**
     * Gets an instance of AppSearchImpl for the given user.
     *
     * <p>This method should only be called by an initialized SearchSession, which has been already
     * created the AppSearchImpl instance for the given user.
     *
     * @param userId The multi-user userId of the device user calling AppSearch
     * @return An initialized {@link AppSearchImpl} for this user
     * @throws IllegalStateException if {@link AppSearchImpl} haven't created for the given user.
     */
    @NonNull
    public AppSearchImpl getAppSearchImpl(@UserIdInt int userId) {
        synchronized (mInstancesLocked) {
            AppSearchImpl instance = mInstancesLocked.get(userId);
            if (instance == null) {
                // Impossible scenario, user cannot call an uninitialized SearchSession,
                // getInstance should always find the instance for the given user and never try to
                // create an instance for this user again.
                throw new IllegalStateException(
                        "AppSearchImpl has never been created for this user: " + userId);
            }
            return instance;
        }
    }

    private AppSearchImpl createImpl(@NonNull Context context, @UserIdInt int userId,
            @Nullable AppSearchLogger logger)
            throws AppSearchException {
        File appSearchDir = getAppSearchDir(userId);
        return AppSearchImpl.create(
                appSearchDir, context, userId, mGlobalQuerierPackage, logger);
    }

    /**
     * Returns the global querier package if it's a system package. Otherwise, empty string.
     *
     * @param context Context of the system service.
     */
    @NonNull
    private static String getGlobalAppSearchDataQuerierPackageName(@NonNull Context context) {
        String globalAppSearchDataQuerierPackage =
                context.getString(R.string.config_globalAppSearchDataQuerierPackage);
        try {
            if (context.getPackageManager()
                    .getPackageInfoAsUser(
                            globalAppSearchDataQuerierPackage,
                            MATCH_FACTORY_ONLY,
                            UserHandle.USER_SYSTEM)
                    == null) {
                return "";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
        return globalAppSearchDataQuerierPackage;
    }
}
