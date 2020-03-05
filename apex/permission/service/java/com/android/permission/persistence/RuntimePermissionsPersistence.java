/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.permission.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.os.UserHandle;

/**
 * Persistence for runtime permissions.
 *
 * TODO(b/147914847): Remove @hide when it becomes the default.
 * @hide
 */
@SystemApi(client = Client.SYSTEM_SERVER)
public interface RuntimePermissionsPersistence {

    /**
     * Read the runtime permissions from persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param user the user to read for
     * @return the runtime permissions read
     */
    @Nullable
    RuntimePermissionsState readForUser(@NonNull UserHandle user);

    /**
     * Write the runtime permissions to persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param runtimePermissions the runtime permissions to write
     * @param user the user to write for
     */
    void writeForUser(@NonNull RuntimePermissionsState runtimePermissions,
            @NonNull UserHandle user);

    /**
     * Delete the runtime permissions from persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param user the user to delete for
     */
    void deleteForUser(@NonNull UserHandle user);

    /**
     * Create a new instance of {@link RuntimePermissionsPersistence} implementation.
     *
     * @return the new instance.
     */
    @NonNull
    static RuntimePermissionsPersistence createInstance() {
        return new RuntimePermissionsPersistenceImpl();
    }
}
