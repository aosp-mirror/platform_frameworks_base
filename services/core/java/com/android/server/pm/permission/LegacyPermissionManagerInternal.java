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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;

/**
 * The internal interface for {@link LegacyPermissionManagerService}.
 */
public interface LegacyPermissionManagerInternal {
    /**
     * Reset the runtime permission state for all users and packages.
     */
    void resetRuntimePermissions();

    /**
     * Sets the dialer application packages provider.
     * @param provider The provider.
     */
    void setDialerAppPackagesProvider(PackagesProvider provider);

    /**
     * Set the location extra packages provider.
     * @param provider The packages provider.
     */
    void setLocationExtraPackagesProvider(PackagesProvider provider);

    /**
     * Sets the location provider packages provider.
     * @param provider The packages provider.
     */
    void setLocationPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SIM call manager packages provider.
     * @param provider The provider.
     */
    void setSimCallManagerPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SMS application packages provider.
     * @param provider The provider.
     */
    void setSmsAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sync adapter packages provider.
     * @param provider The provider.
     */
    void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider);

    /**
     * Sets the Use Open Wifi packages provider.
     * @param provider The packages provider.
     */
    void setUseOpenWifiAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the voice interaction packages provider.
     * @param provider The packages provider.
     */
    void setVoiceInteractionPackagesProvider(PackagesProvider provider);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    void grantDefaultPermissionsToDefaultSimCallManager(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Requests granting of the default permissions to the current default Use Open Wifi app.
     * @param packageName The default use open wifi package name.
     * @param userId The user for which to grant the permissions.
     */
    void grantDefaultPermissionsToDefaultUseOpenWifiApp(@NonNull String packageName,
            @UserIdInt int userId);

    /**
     * Grant the default permissions for a user.
     *
     * @param userId the user ID
     */
    void grantDefaultPermissions(@UserIdInt int userId);

    /**
     * Schedule reading the default permission exceptions file.
     */
    void scheduleReadDefaultPermissionExceptions();

    /**
     * Check whether a particular package should have access to the microphone data from the
     * SoundTrigger.
     *
     * @param uid the uid of the package you are checking against
     * @param packageName the name of the package you are checking against
     * @param attributionTag the attributionTag to attach to the app op transaction
     * @param reason the reason to attach to the app op transaction
     * @return {@code PERMISSION_GRANTED} if the permission is granted,
     *         or {@code PERMISSION_SOFT/HARD DENIED otherwise
     */
    int checkSoundTriggerRecordAudioPermissionForDataDelivery(int uid,
            @NonNull String packageName, @Nullable String attributionTag, @NonNull String reason);

    /**
     * Provider for package names.
     */
    interface PackagesProvider {
        /**
         * Gets the packages for a given user.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(int userId);
    }

    /**
     * Provider for package names.
     */
    interface SyncAdapterPackagesProvider {
        /**
         * Gets the sync adapter packages for given authority and user.
         * @param authority The authority.
         * @param userId The user id.
         * @return The package names.
         */
        String[] getPackages(String authority, int userId);
    }
}
