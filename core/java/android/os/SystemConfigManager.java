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
package android.os;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.SignedPackage;
import android.content.pm.SignedPackageParcel;
import android.util.ArraySet;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Allows apps outside the system process to access various bits of configuration defined in
 * /etc/sysconfig and its counterparts on OEM and vendor partitions.
 *
 * TODO: Intended for access by system mainline modules only. Marking as SystemApi until the
 * module-only API surface is available.
 * @hide
 */
@SystemApi
@SystemService(Context.SYSTEM_CONFIG_SERVICE)
public class SystemConfigManager {
    private static final String TAG = SystemConfigManager.class.getSimpleName();

    private final ISystemConfig mInterface;

    /** @hide **/
    public SystemConfigManager() {
        mInterface = ISystemConfig.Stub.asInterface(
                ServiceManager.getService(Context.SYSTEM_CONFIG_SERVICE));
    }

    /**
     * Returns a set of package names for carrier apps that are preinstalled on the device but
     * should be disabled until the matching carrier's SIM is inserted into the device.
     * @return A set of package names.
     */
    @RequiresPermission(Manifest.permission.READ_CARRIER_APP_INFO)
    public @NonNull Set<String> getDisabledUntilUsedPreinstalledCarrierApps() {
        try {
            List<String> apps = mInterface.getDisabledUntilUsedPreinstalledCarrierApps();
            return new ArraySet<>(apps);
        } catch (RemoteException e) {
            Log.e(TAG, "Caught remote exception");
            return Collections.emptySet();
        }
    }

    /**
     * Returns a map that describes helper apps associated with carrier apps that, like the apps
     * returned by {@link #getDisabledUntilUsedPreinstalledCarrierApps()}, should be disabled until
     * the correct SIM is inserted into the device.
     * @return A map with keys corresponding to package names returned by
     *         {@link #getDisabledUntilUsedPreinstalledCarrierApps()} and values as lists of package
     *         names of helper apps.
     */
    @RequiresPermission(Manifest.permission.READ_CARRIER_APP_INFO)
    public @NonNull Map<String, List<String>>
            getDisabledUntilUsedPreinstalledCarrierAssociatedApps() {
        try {
            return (Map<String, List<String>>)
                    mInterface.getDisabledUntilUsedPreinstalledCarrierAssociatedApps();
        } catch (RemoteException e) {
            Log.e(TAG, "Caught remote exception");
            return Collections.emptyMap();
        }
    }

    /**
     * Returns a map that describes helper apps associated with carrier apps that, like the apps
     * returned by {@link #getDisabledUntilUsedPreinstalledCarrierApps()}, should be disabled until
     * the correct SIM is inserted into the device.
     *
     * <p>TODO(b/159069037) expose this and get rid of the other method that omits SDK version.
     *
     * @return A map with keys corresponding to package names returned by
     *         {@link #getDisabledUntilUsedPreinstalledCarrierApps()} and values as lists of package
     *         names of helper apps and the SDK versions when they were first added.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_CARRIER_APP_INFO)
    public @NonNull Map<String, List<CarrierAssociatedAppEntry>>
            getDisabledUntilUsedPreinstalledCarrierAssociatedAppEntries() {
        try {
            return (Map<String, List<CarrierAssociatedAppEntry>>)
                    mInterface.getDisabledUntilUsedPreinstalledCarrierAssociatedAppEntries();
        } catch (RemoteException e) {
            Log.e(TAG, "Caught remote exception", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Get uids which have been granted given permission in system configuration.
     *
     * The uids and assigning permissions are defined on data/etc/platform.xml
     *
     * @param permissionName The target permission.
     * @return The uids have been granted given permission in system configuration.
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    @NonNull
    public int[] getSystemPermissionUids(@NonNull String permissionName) {
        try {
            return mInterface.getSystemPermissionUids(permissionName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get enabled component for a specific package
     *
     * @param packageName The target package.
     * @return The enabled component
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public List<ComponentName> getEnabledComponentOverrides(@NonNull String packageName) {
        try {
            return mInterface.getEnabledComponentOverrides(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the components that are enabled by default as VR mode listener services.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
    public List<ComponentName> getDefaultVrComponents() {
        try {
            return mInterface.getDefaultVrComponents();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return Collections.emptyList();
    }

    /**
     * Return the packages that are prevented from being disabled, where if
     * disabled it would result in a non-functioning system or similar.
     * @hide
     */
    @NonNull
    public List<String> getPreventUserDisablePackages() {
        try {
            return mInterface.getPreventUserDisablePackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Returns a set of signed packages, represented as (packageName, certificateDigest) pairs, that
     * should be considered "trusted packages" by ECM (Enhanced Confirmation Mode).
     *
     * <p>"Trusted packages" are exempt from ECM (i.e., they will never be considered "restricted").
     *
     * <p>A package will be considered "trusted package" if and only if it *matches* least one of
     * the (*packageName*, *certificateDigest*) pairs in this set, where *matches* means satisfying
     * both of the following:
     *
     * <ol>
     *   <li>The package's name equals *packageName*
     *   <li>The package is, or was ever, signed by *certificateDigest*, according to the package's
     *       {@link android.content.pm.SigningDetails}
     * </ol>
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @FlaggedApi(android.permission.flags.Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED)
    @RequiresPermission(Manifest.permission.MANAGE_ENHANCED_CONFIRMATION_STATES)
    @NonNull
    public Set<SignedPackage> getEnhancedConfirmationTrustedPackages() {
        try {
            List<SignedPackageParcel> parcels = mInterface.getEnhancedConfirmationTrustedPackages();
            return parcels.stream().map(SignedPackage::new).collect(Collectors.toSet());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a set of signed packages, represented as (packageName, certificateDigest) pairs, that
     * should be considered "trusted installers" by ECM (Enhanced Confirmation Mode).
     *
     * <p>"Trusted installers", and all apps installed by a trusted installer, are exempt from ECM
     * (i.e., they will never be considered "restricted").
     *
     * <p>A package will be considered a "trusted installer" if and only if it *matches* least one
     * of the (*packageName*, *certificateDigest*) pairs in this set, where *matches* means
     * satisfying both of the following:
     *
     * <ol>
     *   <li>The package's name equals *packageName*
     *   <li>The package is, or was ever, signed by *certificateDigest*, according to the package's
     *       {@link android.content.pm.SigningDetails}
     * </ol>
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @FlaggedApi(android.permission.flags.Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED)
    @RequiresPermission(Manifest.permission.MANAGE_ENHANCED_CONFIRMATION_STATES)
    @NonNull
    public Set<SignedPackage> getEnhancedConfirmationTrustedInstallers() {
        try {
            List<SignedPackageParcel> parcels =
                    mInterface.getEnhancedConfirmationTrustedInstallers();
            return parcels.stream().map(SignedPackage::new).collect(Collectors.toSet());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
