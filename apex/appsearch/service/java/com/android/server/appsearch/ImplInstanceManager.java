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
import android.annotation.UserIdInt;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import java.io.File;

/**
 * Manages the lifecycle of instances of {@link AppSearchImpl}.
 *
 * <p>These instances are managed per unique device-user.
 */
public final class ImplInstanceManager {
    private static final String APP_SEARCH_DIR = "appSearch";

    private static ImplInstanceManager sImplInstanceManager;

    private final SparseArray<AppSearchImpl> mInstances = new SparseArray<>();
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
     * Gets an instance of AppSearchImpl for the given user.
     *
     * <p>If no AppSearchImpl instance exists for this user, Icing will be initialized and one will
     * be created.
     *
     * @param context The context
     * @param userId The multi-user userId of the device user calling AppSearch
     * @return An initialized {@link AppSearchImpl} for this user
     */
    @NonNull
    public AppSearchImpl getAppSearchImpl(@NonNull Context context, @UserIdInt int userId)
            throws AppSearchException {
        AppSearchImpl instance = mInstances.get(userId);
        if (instance == null) {
            synchronized (ImplInstanceManager.class) {
                instance = mInstances.get(userId);
                if (instance == null) {
                    instance = createImpl(context, userId);
                    mInstances.put(userId, instance);
                }
            }
        }
        return instance;
    }

    private AppSearchImpl createImpl(@NonNull Context context, @UserIdInt int userId)
            throws AppSearchException {
        File appSearchDir = getAppSearchDir(context, userId);
        return AppSearchImpl.create(appSearchDir, context, userId, mGlobalQuerierPackage);
    }

    private static File getAppSearchDir(@NonNull Context context, @UserIdInt int userId) {
        // See com.android.internal.app.ChooserActivity::getPinnedSharedPrefs
        File userCeDir =
                Environment.getDataUserCePackageDirectory(
                        StorageManager.UUID_PRIVATE_INTERNAL, userId, context.getPackageName());
        return new File(userCeDir, APP_SEARCH_DIR);
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
