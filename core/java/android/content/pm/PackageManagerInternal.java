/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.pm;

import android.content.ComponentName;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.SparseArray;

import java.util.List;

/**
 * Package manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class PackageManagerInternal {

    /**
     * Provider for package names.
     */
    public interface PackagesProvider {

        /**
         * Gets the packages for a given user.
         * @param userId The user id.
         * @return The package names.
         */
        public String[] getPackages(int userId);
    }

    /**
     * Provider for package names.
     */
    public interface SyncAdapterPackagesProvider {

        /**
         * Gets the sync adapter packages for given authority and user.
         * @param authority The authority.
         * @param userId The user id.
         * @return The package names.
         */
        public String[] getPackages(String authority, int userId);
    }

    /**
     * Sets the location provider packages provider.
     * @param provider The packages provider.
     */
    public abstract void setLocationPackagesProvider(PackagesProvider provider);

    /**
     * Sets the voice interaction packages provider.
     * @param provider The packages provider.
     */
    public abstract void setVoiceInteractionPackagesProvider(PackagesProvider provider);

    /**
     * Sets the SMS packages provider.
     * @param provider The packages provider.
     */
    public abstract void setSmsAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the dialer packages provider.
     * @param provider The packages provider.
     */
    public abstract void setDialerAppPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sim call manager packages provider.
     * @param provider The packages provider.
     */
    public abstract void setSimCallManagerPackagesProvider(PackagesProvider provider);

    /**
     * Sets the sync adapter packages provider.
     * @param provider The provider.
     */
    public abstract void setSyncAdapterPackagesprovider(SyncAdapterPackagesProvider provider);

    /**
     * Requests granting of the default permissions to the current default SMS app.
     * @param packageName The default SMS package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId);

    /**
     * Requests granting of the default permissions to the current default dialer app.
     * @param packageName The default dialer package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId);

    /**
     * Requests granting of the default permissions to the current default sim call manager.
     * @param packageName The default sim call manager package name.
     * @param userId The user for which to grant the permissions.
     */
    public abstract void grantDefaultPermissionsToDefaultSimCallManager(String packageName,
            int userId);

    /**
     * Sets a list of apps to keep in PM's internal data structures and as APKs even if no user has
     * currently installed it. The apps are not preloaded.
     * @param packageList List of package names to keep cached.
     */
    public abstract void setKeepUninstalledPackages(List<String> packageList);

    /**
     * Gets whether some of the permissions used by this package require a user
     * review before any of the app components can run.
     * @param packageName The package name for which to check.
     * @param userId The user under which to check.
     * @return True a permissions review is required.
     */
    public abstract boolean isPermissionsReviewRequired(String packageName, int userId);

    /**
     * Gets all of the information we know about a particular package.
     *
     * @param packageName The package name to find.
     * @param userId The user under which to check.
     *
     * @return An {@link ApplicationInfo} containing information about the
     *         package.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ApplicationInfo getApplicationInfo(String packageName, int userId);

    /**
     * Interface to {@link com.android.server.pm.PackageManagerService#getHomeActivitiesAsUser}.
     */
    public abstract ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId);

    /**
     * Called by DeviceOwnerManagerService to set the package names of device owner and profile
     * owners.
     */
    public abstract void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwner, SparseArray<String> profileOwners);

    /**
     * Whether a package's data be cleared.
     */
    public abstract boolean canPackageBeWiped(int userId, String packageName);
}
