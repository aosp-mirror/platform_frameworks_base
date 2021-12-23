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

package android.app.compat;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.Compatibility;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;

import java.util.Map;
import java.util.Set;

/**
 * CompatChanges APIs - to be used by platform code only (including mainline
 * modules).
 *
 * @hide
 */
@SystemApi
public final class CompatChanges {
    private static final ChangeIdStateCache QUERY_CACHE = new ChangeIdStateCache();

    private CompatChanges() {}

    /**
     * Query if a given compatibility change is enabled for the current process. This method is
     * intended to be called by code running inside a process of the affected app only.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * @param changeId The ID of the compatibility change in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    public static boolean isChangeEnabled(long changeId) {
        return Compatibility.isChangeEnabled(changeId);
    }

    /**
     * Same as {@code #isChangeEnabled(long)}, except this version should be called on behalf of an
     * app from a different process that's performing work for the app.
     *
     * <p> Note that this involves a binder call to the system server (unless running in the system
     * server). If the binder call fails, a {@code RuntimeException} will be thrown.
     *
     * @param changeId    The ID of the compatibility change in question.
     * @param packageName The package name of the app in question.
     * @param user        The user that the operation is done for.
     * @return {@code true} if the change is enabled for the current app.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
            android.Manifest.permission.LOG_COMPAT_CHANGE})
    public static boolean isChangeEnabled(long changeId, @NonNull String packageName,
            @NonNull UserHandle user) {
        return QUERY_CACHE.query(ChangeIdStateQuery.byPackageName(changeId, packageName,
                                                           user.getIdentifier()));
    }

    /**
     * Same as {@code #isChangeEnabled(long)}, except this version should be called on behalf of an
     * app from a different process that's performing work for the app.
     *
     * <p> Note that this involves a binder call to the system server (unless running in the system
     * server). If the binder call fails, {@code RuntimeException}  will be thrown.
     *
     * <p> Returns {@code true} if there are no installed packages for the required UID, or if the
     * change is enabled for ALL of the installed packages associated with the provided UID. Please
     * use a more specific API if you want a different behaviour for multi-package UIDs.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param uid      The UID of the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
            android.Manifest.permission.LOG_COMPAT_CHANGE})
    public static boolean isChangeEnabled(long changeId, int uid) {
        return QUERY_CACHE.query(ChangeIdStateQuery.byUid(changeId, uid));
    }

    /**
     * Equivalent to calling {@link #putPackageOverrides(String, Map)} on each entry in {@code
     * packageNameToOverrides}, but the state of the compat config will be updated only once
     * instead of for each package.
     *
     * @param packageNameToOverrides A map from package name to a map from change ID to the
     *                               override applied for that package name and change ID.
     */
    @RequiresPermission(android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public static void putAllPackageOverrides(
            @NonNull Map<String, Map<Long, PackageOverride>> packageNameToOverrides) {
        ArrayMap<String, CompatibilityOverrideConfig> packageNameToConfig = new ArrayMap<>();
        for (String packageName : packageNameToOverrides.keySet()) {
            packageNameToConfig.put(packageName,
                    new CompatibilityOverrideConfig(packageNameToOverrides.get(packageName)));
        }
        CompatibilityOverridesByPackageConfig config = new CompatibilityOverridesByPackageConfig(
                packageNameToConfig);
        try {
            QUERY_CACHE.getPlatformCompatService().putAllOverridesOnReleaseBuilds(config);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Associates app compat overrides with the given package and their respective change IDs.
     * This will check whether the caller is allowed to perform this operation on the given apk and
     * build. Only the installer package is allowed to set overrides on a non-debuggable final
     * build and a non-test apk.
     *
     * <p>Note that calling this method doesn't remove previously added overrides for the given
     * package if their change ID isn't in the given map, only replaces those that have the same
     * change ID.
     *
     * @param packageName The package name of the app in question.
     * @param overrides A map from change ID to the override applied for this change ID.
     */
    @RequiresPermission(android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public static void putPackageOverrides(@NonNull String packageName,
            @NonNull Map<Long, PackageOverride> overrides) {
        CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(overrides);
        try {
            QUERY_CACHE.getPlatformCompatService()
                .putOverridesOnReleaseBuilds(config, packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Equivalent to calling {@link #removePackageOverrides(String, Set)} on each entry in {@code
     * packageNameToOverridesToRemove}, but the state of the compat config will be updated only once
     * instead of for each package.
     *
     * @param packageNameToOverridesToRemove A map from package name to a set of change IDs for
     *                                       which to remove overrides for that package name.
     */
    @RequiresPermission(android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public static void removeAllPackageOverrides(
            @NonNull Map<String, Set<Long>> packageNameToOverridesToRemove) {
        ArrayMap<String, CompatibilityOverridesToRemoveConfig> packageNameToConfig =
                new ArrayMap<>();
        for (String packageName : packageNameToOverridesToRemove.keySet()) {
            packageNameToConfig.put(packageName,
                    new CompatibilityOverridesToRemoveConfig(
                            packageNameToOverridesToRemove.get(packageName)));
        }
        CompatibilityOverridesToRemoveByPackageConfig config =
                new CompatibilityOverridesToRemoveByPackageConfig(packageNameToConfig);
        try {
            QUERY_CACHE.getPlatformCompatService().removeAllOverridesOnReleaseBuilds(config);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes app compat overrides for the given package. This will check whether the caller is
     * allowed to perform this operation on the given apk and build. Only the installer package is
     * allowed to clear overrides on a non-debuggable final build and a non-test apk.
     *
     * <p>Note that calling this method with an empty set is a no-op and no overrides will be
     * removed for the given package.
     *
     * @param packageName The package name of the app in question.
     * @param overridesToRemove A set of change IDs for which to remove overrides.
     */
    @RequiresPermission(android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public static void removePackageOverrides(@NonNull String packageName,
            @NonNull Set<Long> overridesToRemove) {
        CompatibilityOverridesToRemoveConfig config = new CompatibilityOverridesToRemoveConfig(
                overridesToRemove);
        try {
            QUERY_CACHE.getPlatformCompatService()
                .removeOverridesOnReleaseBuilds(config, packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}
