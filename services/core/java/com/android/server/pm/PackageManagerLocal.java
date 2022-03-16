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

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

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
    public @interface StorageFlags {}

    /**
     * Reconcile sdk data sub-directories for the given {@code packagName}.
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
}
