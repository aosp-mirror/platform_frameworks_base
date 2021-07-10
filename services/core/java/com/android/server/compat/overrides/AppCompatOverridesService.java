/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.compat.overrides;

import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.provider.DeviceConfig.NAMESPACE_APP_COMPAT_OVERRIDES;

import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_OWNED_CHANGE_IDS;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_REMOVE_OVERRIDES;

import static java.util.Collections.emptySet;

import android.annotation.Nullable;
import android.app.compat.PackageOverride;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.SystemService;
import com.android.server.compat.overrides.AppCompatOverridesParser.PackageOverrides;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for applying per-app compat overrides delivered via Device Config.
 *
 * <p>The service listens both on changes to supported Device Config namespaces and on package
 * added/changed/removed events, and applies overrides accordingly.
 *
 * @hide
 */
public final class AppCompatOverridesService {
    private static final String TAG = "AppCompatOverridesService";

    private static final List<String> SUPPORTED_NAMESPACES = Arrays.asList(
            NAMESPACE_APP_COMPAT_OVERRIDES);

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final IPlatformCompat mPlatformCompat;
    private final List<String> mSupportedNamespaces;
    private final List<DeviceConfigListener> mDeviceConfigListeners;
    private final AppCompatOverridesParser mOverridesParser;

    private AppCompatOverridesService(Context context) {
        this(context, IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE)), SUPPORTED_NAMESPACES);
    }

    @VisibleForTesting
    AppCompatOverridesService(Context context, IPlatformCompat platformCompat,
            List<String> supportedNamespaces) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mPlatformCompat = platformCompat;
        mSupportedNamespaces = supportedNamespaces;
        mDeviceConfigListeners = new ArrayList<>();
        mOverridesParser = new AppCompatOverridesParser(mPackageManager);
    }

    @Override
    public void finalize() {
        unregisterDeviceConfigListeners();
    }

    @VisibleForTesting
    void registerDeviceConfigListeners() {
        for (String namespace : mSupportedNamespaces) {
            DeviceConfigListener listener = new DeviceConfigListener(namespace);
            DeviceConfig.addOnPropertiesChangedListener(namespace, mContext.getMainExecutor(),
                    listener);
            mDeviceConfigListeners.add(listener);
        }
    }

    private void unregisterDeviceConfigListeners() {
        for (DeviceConfigListener listener : mDeviceConfigListeners) {
            DeviceConfig.removeOnPropertiesChangedListener(listener);
        }
    }

    /**
     * Same as {@link #applyOverrides(Properties, Map)} except all properties of the given {@code
     * namespace} are fetched via {@link DeviceConfig#getProperties}.
     */
    private void applyAllOverrides(String namespace,
            Map<String, Set<Long>> packageToChangeIdsToSkip) {
        applyOverrides(DeviceConfig.getProperties(namespace), packageToChangeIdsToSkip);
    }

    /**
     * Iterates all package override flags in the given {@code properties}, and for each flag whose
     * package is installed on the device, parses its value and applies the overrides in it with
     * respect to the package's current installed version.
     */
    private void applyOverrides(Properties properties,
            Map<String, Set<Long>> packageToChangeIdsToSkip) {
        Set<String> packageNames = new ArraySet<>(properties.getKeyset());
        packageNames.remove(FLAG_OWNED_CHANGE_IDS);
        packageNames.remove(FLAG_REMOVE_OVERRIDES);
        for (String packageName : packageNames) {
            Long versionCode = getVersionCodeOrNull(packageName);
            if (versionCode == null) {
                // Package isn't installed yet.
                continue;
            }

            applyPackageOverrides(properties.getString(packageName, /* defaultValue= */ ""),
                    packageName, versionCode,
                    packageToChangeIdsToSkip.getOrDefault(packageName, emptySet()));
        }
    }

    /**
     * Calls {@link AppCompatOverridesParser#parsePackageOverrides} on the given arguments, adds the
     * resulting {@link PackageOverrides#overridesToAdd} via {@link
     * IPlatformCompat#putOverridesOnReleaseBuilds}, and removes the resulting {@link
     * PackageOverrides#overridesToRemove} via {@link
     * IPlatformCompat#removeOverridesOnReleaseBuilds}.
     */
    private void applyPackageOverrides(String configStr, String packageName,
            long versionCode, Set<Long> changeIdsToSkip) {
        PackageOverrides packageOverrides = AppCompatOverridesParser.parsePackageOverrides(
                configStr, versionCode, changeIdsToSkip);
        putPackageOverrides(packageName, packageOverrides.overridesToAdd);
        removePackageOverrides(packageName, packageOverrides.overridesToRemove);
    }

    /**
     * Calls {@link IPlatformCompat#removeOverridesOnReleaseBuilds} on each package name and
     * respective change IDs in {@code overridesToRemove}.
     */
    private void removeOverrides(Map<String, Set<Long>> overridesToRemove) {
        for (Map.Entry<String, Set<Long>> packageNameAndOverrides : overridesToRemove.entrySet()) {
            removePackageOverrides(packageNameAndOverrides.getKey(),
                    packageNameAndOverrides.getValue());
        }
    }

    /**
     * Fetches the value of {@link AppCompatOverridesParser#FLAG_REMOVE_OVERRIDES} for the given
     * {@code namespace} and parses it into a map from package name to a set of change IDs to
     * remove for that package.
     */
    private Map<String, Set<Long>> getOverridesToRemove(String namespace) {
        return mOverridesParser.parseRemoveOverrides(
                DeviceConfig.getString(namespace, FLAG_REMOVE_OVERRIDES, /* defaultValue= */ ""),
                getOwnedChangeIds(namespace));
    }

    /**
     * Fetches the value of {@link AppCompatOverridesParser#FLAG_OWNED_CHANGE_IDS} for the given
     * {@code namespace} and parses it into a set of change IDs.
     */
    private static Set<Long> getOwnedChangeIds(String namespace) {
        return AppCompatOverridesParser.parseOwnedChangeIds(
                DeviceConfig.getString(namespace, FLAG_OWNED_CHANGE_IDS, /* defaultValue= */ ""));
    }

    private void putPackageOverrides(String packageName,
            Map<Long, PackageOverride> overridesToAdd) {
        if (overridesToAdd.isEmpty()) {
            return;
        }
        CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(overridesToAdd);
        try {
            mPlatformCompat.putOverridesOnReleaseBuilds(config, packageName);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call IPlatformCompat#putOverridesOnReleaseBuilds", e);
        }
    }

    private void removePackageOverrides(String packageName, Set<Long> overridesToRemove) {
        if (overridesToRemove.isEmpty()) {
            return;
        }
        CompatibilityOverridesToRemoveConfig config = new CompatibilityOverridesToRemoveConfig(
                overridesToRemove);
        try {
            mPlatformCompat.removeOverridesOnReleaseBuilds(config, packageName);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call IPlatformCompat#removeOverridesOnReleaseBuilds", e);
        }
    }

    @Nullable
    private Long getVersionCodeOrNull(String packageName) {
        try {
            ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(packageName,
                    MATCH_ANY_USER);
            return applicationInfo.longVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // Package isn't installed yet.
            return null;
        }
    }

    /**
     * SystemService lifecycle for AppCompatOverridesService.
     *
     * @hide
     */
    public static final class Lifecycle extends SystemService {
        private AppCompatOverridesService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new AppCompatOverridesService(getContext());
            mService.registerDeviceConfigListeners();
        }
    }

    /**
     * A {@link DeviceConfig.OnPropertiesChangedListener} that listens on changes to a given
     * namespace and adds/removes overrides according to the changed flags.
     */
    private final class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        private final String mNamespace;

        private DeviceConfigListener(String namespace) {
            mNamespace = namespace;
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            boolean removeOverridesFlagChanged = properties.getKeyset().contains(
                    FLAG_REMOVE_OVERRIDES);
            boolean ownedChangedIdsFlagChanged = properties.getKeyset().contains(
                    FLAG_OWNED_CHANGE_IDS);

            Map<String, Set<Long>> overridesToRemove = getOverridesToRemove(mNamespace);
            if (removeOverridesFlagChanged || ownedChangedIdsFlagChanged) {
                // In both cases it's possible that overrides that weren't removed before should
                // now be removed.
                removeOverrides(overridesToRemove);
            }

            if (removeOverridesFlagChanged) {
                // We need to re-apply all overrides in the namespace since the remove overrides
                // flag might have blocked some of them from being applied before.
                applyAllOverrides(mNamespace, overridesToRemove);
            } else {
                applyOverrides(properties, overridesToRemove);
            }
        }
    }
}
