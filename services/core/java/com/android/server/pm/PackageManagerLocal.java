/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.UserHandle;

import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.SharedUserApi;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * In-process API for server side PackageManager related infrastructure.
 *
 * For now, avoiding adding methods that rely on package data until we solve the snapshot
 * consistency problem.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageManagerLocal {

    /**
     * Indicates if operation should include device encrypted storage.
     */
    int FLAG_STORAGE_DE = Installer.FLAG_STORAGE_DE;
    /**
     * Indicates if operation should include credential encrypted storage.
     */
    int FLAG_STORAGE_CE = Installer.FLAG_STORAGE_CE;

    /**
     * Constants for use with {@link #reconcileSdkData} to specify which storage areas should be
     * included for operation.
     *
     * @hide
     */
    @IntDef(prefix = "FLAG_STORAGE_",  value = {
            FLAG_STORAGE_DE,
            FLAG_STORAGE_CE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface StorageFlags {}

    /**
     * Reconcile sdk data sub-directories for the given {@code packageName}.
     *
     * Sub directories are created if they do not exist already. If there is an existing per-
     * sdk directory that is missing from {@code subDirNames}, then it is removed.
     *
     * Sdk package path is created if it doesn't exist before creating per-sdk directories.
     *
     * @param volumeUuid the volume in which the sdk data should be prepared.
     * @param packageName package name of the app for which sdk data directory will be prepared.
     * @param subDirNames names of sub directories that should be reconciled against.
     * @param userId id of the user to whom the package belongs to.
     * @param appId id of the package.
     * @param previousAppId previous id of the package if package is being updated.
     * @param flags flags from StorageManager to indicate which storage areas should be included.
     * @param seInfo seInfo tag to be used for selinux policy.
     * @throws IOException If any error occurs during the operation.
     */
    void reconcileSdkData(@Nullable String volumeUuid, @NonNull String packageName,
            @NonNull List<String> subDirNames, int userId, int appId, int previousAppId,
            @NonNull String seInfo, @StorageFlags int flags) throws IOException;

    /**
     * Provides a snapshot scoped class to access snapshot-aware APIs. Should be short-term use and
     * closed as soon as possible.
     * <p/>
     * All reachable types in the snapshot are read-only.
     * <p/>
     * The snapshot assumes the caller is acting on behalf of the system and will not filter any
     * results.
     */
    @NonNull
    UnfilteredSnapshot withUnfilteredSnapshot();

    /**
     * {@link #withFilteredSnapshot(int, UserHandle)} that infers the UID and user from the
     * caller through {@link Binder#getCallingUid()} and {@link Binder#getCallingUserHandle()}.
     *
     * @see #withFilteredSnapshot(int, UserHandle)
     */
    @NonNull
    FilteredSnapshot withFilteredSnapshot();

    /**
     * Provides a snapshot scoped class to access snapshot-aware APIs. Should be short-term use and
     * closed as soon as possible.
     * <p/>
     * All reachable types in the snapshot are read-only.
     *
     * @param callingUid The caller UID to filter results based on. This includes package visibility
     *                   and permissions, including cross-user enforcement.
     * @param user       The user to query as, should usually be the user that the caller was
     *                   invoked from.
     */
    @SuppressWarnings("UserHandleName") // Ignore naming convention, not invoking action as user
    @NonNull
    FilteredSnapshot withFilteredSnapshot(int callingUid, @NonNull UserHandle user);

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    interface UnfilteredSnapshot extends AutoCloseable {

        /**
         * Allows re-use of this snapshot, but in a filtered context. This allows a caller to invoke
         * itself as multiple other actual callers without having to re-take a snapshot.
         * <p/>
         * Note that closing the parent snapshot closes any filtered children generated from it.
         *
         * @return An isolated instance of {@link FilteredSnapshot} which can be closed without
         * affecting this parent snapshot or any sibling snapshots.
         */
        @SuppressWarnings("UserHandleName") // Ignore naming convention, not invoking action as user
        @NonNull
        FilteredSnapshot filtered(int callingUid, @NonNull UserHandle user);

        /**
         * Returns a map of all {@link PackageState PackageStates} on the device.
         *
         * @return Mapping of package name to {@link PackageState}.
         */
        @NonNull
        Map<String, PackageState> getPackageStates();

        /**
         * Returns a map of all {@link SharedUserApi SharedUsers} on the device.
         *
         * @return Mapping of shared user name to {@link SharedUserApi}.
         *
         * @hide Pending API
         */
        @NonNull
        Map<String, SharedUserApi> getSharedUsers();

        /**
         * Returns a map of all disabled system {@link PackageState PackageStates} on the device.
         *
         * @return Mapping of package name to disabled system {@link PackageState}.
         *
         * @hide Pending API
         */
        @NonNull
        Map<String, PackageState> getDisabledSystemPackageStates();

        @Override
        void close();
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    interface FilteredSnapshot extends AutoCloseable {

        /**
         * @return {@link PackageState} for the {@code packageName}, filtered if applicable.
         */
        @Nullable
        PackageState getPackageState(@NonNull String packageName);

        /**
         * Returns a map of all {@link PackageState PackageStates} on the device.
         * <p>
         * This will cause app visibility filtering to be invoked on each state on the device,
         * which can be expensive. Prefer {@link #getPackageState(String)} if possible.
         *
         * @return Mapping of package name to {@link PackageState}.
         */
        @NonNull
        Map<String, PackageState> getPackageStates();

        @Override
        void close();
    }
}
