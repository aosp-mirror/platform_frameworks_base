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

package android.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;

/**
 * Internal interfaces to be used by other components within the system server.
 *
 * <p>Only for use within the system server.
 *
 * @hide
 */
public interface PermissionManagerInternal {
    /**
     * Get the state of the runtime permissions as a blob.
     *
     * @param userId The user ID the data should be extracted for
     *
     * @return the state as a blob
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    @Nullable
    byte[] backupRuntimePermissions(@UserIdInt int userId);

    /**
     * Restore a permission state previously backed up via {@link #backupRuntimePermissions}.
     * <p>
     * If not all state can be restored, the un-restorable state will be delayed and can be
     * retried via {@link #restoreDelayedRuntimePermissions}.
     *
     * @param backup the state as a blob
     * @param userId the user ID the data should be restored for
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void restoreRuntimePermissions(@NonNull byte[] backup, @UserIdInt int userId);

    /**
     * Try to apply permission backup of a package that was previously not applied.
     *
     * @param packageName the package that is newly installed
     * @param userId the user ID the package is installed for
     *
     * @see #restoreRuntimePermissions
     */
    //@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    void restoreDelayedRuntimePermissions(@NonNull String packageName,
            @UserIdInt int userId);
}
