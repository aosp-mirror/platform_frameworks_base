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
import android.os.UserHandle;

/**
 * Internal interfaces to be used by other components within the system server.
 *
 * <p>Only for use within the system server.
 *
 * @hide
 */
public abstract class PermissionManagerInternal {
    /**
     * Get the state of the runtime permissions as xml file.
     *
     * @param user The user the data should be extracted for
     *
     * @return The state as a xml file
     */
    public abstract @Nullable byte[] backupRuntimePermissions(@NonNull UserHandle user);

    /**
     * Restore a permission state previously backed up via {@link #backupRuntimePermissions}.
     *
     * <p>If not all state can be restored, the un-restoreable state will be delayed and can be
     * re-tried via {@link #restoreDelayedRuntimePermissions}.
     *
     * @param backup The state as an xml file
     * @param user The user the data should be restored for
     */
    public abstract void restoreRuntimePermissions(@NonNull byte[] backup,
            @NonNull UserHandle user);

    /**
     * Try to apply permission backup of a package that was previously not applied.
     *
     * @param packageName The package that is newly installed
     * @param user The user the package is installed for
     *
     * @see #restoreRuntimePermissions
     */
    public abstract void restoreDelayedRuntimePermissions(@NonNull String packageName,
            @NonNull UserHandle user);
}
