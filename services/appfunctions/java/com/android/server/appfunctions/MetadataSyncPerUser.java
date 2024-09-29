/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import android.annotation.Nullable;
import android.app.appsearch.AppSearchManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/** A Singleton class that manages per-user metadata sync adapters. */
public final class MetadataSyncPerUser {
    private static final String TAG = MetadataSyncPerUser.class.getSimpleName();

    /** A map of per-user adapter for synchronizing appFunction metadata. */
    @GuardedBy("sLock")
    private static final SparseArray<MetadataSyncAdapter> sPerUserMetadataSyncAdapter =
            new SparseArray<>();

    private static final Object sLock = new Object();

    /**
     * Returns the per-user metadata sync adapter for the given user.
     *
     * @param user The user for which to get the metadata sync adapter.
     * @param userContext The user context for the given user.
     * @return The metadata sync adapter for the given user.
     */
    @Nullable
    public static MetadataSyncAdapter getPerUserMetadataSyncAdapter(
            UserHandle user, Context userContext) {
        synchronized (sLock) {
            MetadataSyncAdapter metadataSyncAdapter =
                    sPerUserMetadataSyncAdapter.get(user.getIdentifier(), null);
            if (metadataSyncAdapter == null) {
                AppSearchManager perUserAppSearchManager =
                        userContext.getSystemService(AppSearchManager.class);
                PackageManager perUserPackageManager = userContext.getPackageManager();
                if (perUserAppSearchManager != null) {
                    metadataSyncAdapter =
                            new MetadataSyncAdapter(perUserPackageManager, perUserAppSearchManager);
                    sPerUserMetadataSyncAdapter.put(user.getIdentifier(), metadataSyncAdapter);
                    return metadataSyncAdapter;
                }
            }
            return metadataSyncAdapter;
        }
    }

    /**
     * Removes the per-user metadata sync adapter for the given user.
     *
     * @param user The user for which to remove the metadata sync adapter.
     */
    public static void removeUserSyncAdapter(UserHandle user) {
        synchronized (sLock) {
            MetadataSyncAdapter metadataSyncAdapter =
                    sPerUserMetadataSyncAdapter.get(user.getIdentifier(), null);
            if (metadataSyncAdapter != null) {
                metadataSyncAdapter.shutDown();
                sPerUserMetadataSyncAdapter.remove(user.getIdentifier());
            }
        }
    }
}
