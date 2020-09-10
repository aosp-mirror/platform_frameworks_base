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

package com.android.role.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.os.UserHandle;

/**
 * Persistence for roles.
 *
 * TODO(b/147914847): Remove @hide when it becomes the default.
 * @hide
 */
@SystemApi(client = Client.SYSTEM_SERVER)
public interface RolesPersistence {

    /**
     * Read the roles from persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param user the user to read for
     * @return the roles read
     */
    @Nullable
    RolesState readForUser(@NonNull UserHandle user);

    /**
     * Write the roles to persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param roles the roles to write
     * @param user the user to write for
     */
    void writeForUser(@NonNull RolesState roles, @NonNull UserHandle user);

    /**
     * Delete the roles from persistence.
     *
     * This will perform I/O operations synchronously.
     *
     * @param user the user to delete for
     */
    void deleteForUser(@NonNull UserHandle user);

    /**
     * Create a new instance of {@link RolesPersistence} implementation.
     *
     * @return the new instance.
     */
    @NonNull
    static RolesPersistence createInstance() {
        return new RolesPersistenceImpl();
    }
}
