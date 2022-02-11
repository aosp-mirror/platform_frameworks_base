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

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.provider.DeviceConfig.NAMESPACE_APP_COMPAT_OVERRIDES;

import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_OWNED_CHANGE_IDS;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_REMOVE_OVERRIDES;

import static java.util.Collections.emptySet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.PackageOverride;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
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
    private final AppCompatOverridesParser mOverridesParser;
    private final PackageReceiver mPackageReceiver;
    private final List<DeviceConfigListener> mDeviceConfigListeners;

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
        mOverridesParser = new AppCompatOverridesParser(mPackageManager);
        mPackageReceiver = new PackageReceiver(mContext);
        mDeviceConfigListeners = new ArrayList<>();
        for (String namespace : mSupportedNamespaces) {
            mDeviceConfigListeners.add(new DeviceConfigListener(mContext, namespace));
        }
    }

    @Override
    public void finalize() {
        unregisterDeviceConfigListeners();
        unregisterPackageReceiver();
    }

    @VisibleForTesting
    void registerDeviceConfigListeners() {
        for (DeviceConfigListener listener : mDeviceConfigListeners) {
            listener.register();
        }
    }

    private void unregisterDeviceConfigListeners() {
        for (DeviceConfigListener listener : mDeviceConfigListeners) {
            listener.unregister();
        }
    }

    @VisibleForTesting
    void registerPackageReceiver() {
        mPackageReceiver.register();
    }

    private void unregisterPackageReceiver() {
        mPackageReceiver.unregister();
    }

    /**
     * Same as {@link #applyOverrides(Properties, Set, Map)} except all properties of the given
     * {@code namespace} are fetched via {@link DeviceConfig#getProperties}.
     */
    private void applyAllOverrides(String namespace, Set<Long> ownedChangeIds,
            Map<String, Set<Long>> packageToChangeIdsToSkip) {
        applyOverrides(DeviceConfig.getProperties(namespace), ownedChangeIds,
                packageToChangeIdsToSkip);
    }

    /**
     * Iterates all package override flags in the given {@code properties}, and for each flag whose
     * package is installed on the device, parses its value and adds the overrides in it with
     * respect to the package's current installed version.
     *
     * <p>In addition, for each package, removes any override that wasn't just added, whose change
     * ID is in {@code ownedChangeIds} but not in the respective set in {@code
     * packageToChangeIdsToSkip}.
     */
    private void applyOverrides(Properties properties, Set<Long> ownedChangeIds,
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
                    packageName, versionCode, ownedChangeIds,
                    packageToChangeIdsToSkip.getOrDefault(packageName, emptySet()),
                    /* removeOtherOwnedOverrides= */ true);
        }
    }

    /**
     * Adds all overrides in all supported namespaces for the given {@code packageName}.
     */
    private void addAllPackageOverrides(String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        if (versionCode == null) {
            return;
        }

        for (String namespace : mSupportedNamespaces) {
            // We apply overrides for each namespace separately so that if there is a failure for
            // one namespace, the other namespaces won't be affected.
            Set<Long> ownedChangeIds = getOwnedChangeIds(namespace);
            applyPackageOverrides(
                    DeviceConfig.getString(namespace, packageName, /* defaultValue= */ ""),
                    packageName, versionCode, ownedChangeIds,
                    getOverridesToRemove(namespace, ownedChangeIds).getOrDefault(packageName,
                            emptySet()), /* removeOtherOwnedOverrides */ false);
        }
    }

    /**
     * Calls {@link AppCompatOverridesParser#parsePackageOverrides} on the given arguments and adds
     * the resulting overrides via {@link IPlatformCompat#putOverridesOnReleaseBuilds}.
     *
     * <p>In addition, if {@code removeOtherOwnedOverrides} is true, removes any override that
     * wasn't just added, whose change ID is in {@code ownedChangeIds} but not in {@code
     * changeIdsToSkip}, via {@link IPlatformCompat#removeOverridesOnReleaseBuilds}.
     */
    private void applyPackageOverrides(String configStr, String packageName, long versionCode,
            Set<Long> ownedChangeIds, Set<Long> changeIdsToSkip,
            boolean removeOtherOwnedOverrides) {
        Map<Long, PackageOverride> overridesToAdd = AppCompatOverridesParser.parsePackageOverrides(
                configStr, versionCode, changeIdsToSkip);
        putPackageOverrides(packageName, overridesToAdd);

        if (!removeOtherOwnedOverrides) {
            return;
        }
        Set<Long> overridesToRemove = new ArraySet<>();
        for (Long changeId : ownedChangeIds) {
            if (!overridesToAdd.containsKey(changeId) && !changeIdsToSkip.contains(changeId)) {
                overridesToRemove.add(changeId);
            }
        }
        removePackageOverrides(packageName, overridesToRemove);
    }

    /**
     * Removes all owned overrides in all supported namespaces for the given {@code packageName}.
     *
     * <p>If a certain namespace doesn't have a package override flag for the given {@code
     * packageName}, that namespace is skipped.</p>
     */
    private void removeAllPackageOverrides(String packageName) {
        for (String namespace : mSupportedNamespaces) {
            if (DeviceConfig.getString(namespace, packageName, /* defaultValue= */ "").isEmpty()) {
                // No overrides for this package in this namespace.
                continue;
            }
            // We remove overrides for each namespace separately so that if there is a failure for
            // one namespace, the other namespaces won't be affected.
            removePackageOverrides(packageName, getOwnedChangeIds(namespace));
        }
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
    private Map<String, Set<Long>> getOverridesToRemove(String namespace,
            Set<Long> ownedChangeIds) {
        return mOverridesParser.parseRemoveOverrides(
                DeviceConfig.getString(namespace, FLAG_REMOVE_OVERRIDES, /* defaultValue= */ ""),
                ownedChangeIds);
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

    private boolean isInstalledForAnyUser(String packageName) {
        return getVersionCodeOrNull(packageName) != null;
    }

    @Nullable
    private Long getVersionCodeOrNull(String packageName) {
        try {
            ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(packageName,
                    MATCH_ANY_USER);
            return applicationInfo.longVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // Package isn't installed for any user.
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
            mService.registerPackageReceiver();
        }
    }

    /**
     * A {@link DeviceConfig.OnPropertiesChangedListener} that listens on changes to a given
     * namespace and adds/removes overrides according to the changed flags.
     */
    private final class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        private final Context mContext;
        private final String mNamespace;

        private DeviceConfigListener(Context context, String namespace) {
            mContext = context;
            mNamespace = namespace;
        }

        private void register() {
            DeviceConfig.addOnPropertiesChangedListener(mNamespace, mContext.getMainExecutor(),
                    this);
        }

        private void unregister() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            boolean removeOverridesFlagChanged = properties.getKeyset().contains(
                    FLAG_REMOVE_OVERRIDES);
            boolean ownedChangedIdsFlagChanged = properties.getKeyset().contains(
                    FLAG_OWNED_CHANGE_IDS);

            Set<Long> ownedChangeIds = getOwnedChangeIds(mNamespace);
            Map<String, Set<Long>> overridesToRemove = getOverridesToRemove(mNamespace,
                    ownedChangeIds);
            if (removeOverridesFlagChanged || ownedChangedIdsFlagChanged) {
                // In both cases it's possible that overrides that weren't removed before should
                // now be removed.
                removeOverrides(overridesToRemove);
            }

            if (removeOverridesFlagChanged) {
                // We need to re-apply all overrides in the namespace since the remove overrides
                // flag might have blocked some of them from being applied before.
                applyAllOverrides(mNamespace, ownedChangeIds, overridesToRemove);
            } else {
                applyOverrides(properties, ownedChangeIds, overridesToRemove);
            }
        }
    }

    /**
     * A {@link BroadcastReceiver} that listens on package added/changed/removed events and
     * adds/removes overrides according to the corresponding Device Config flags.
     */
    private final class PackageReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final IntentFilter mIntentFilter;

        private PackageReceiver(Context context) {
            mContext = context;
            mIntentFilter = new IntentFilter();
            mIntentFilter.addAction(ACTION_PACKAGE_ADDED);
            mIntentFilter.addAction(ACTION_PACKAGE_CHANGED);
            mIntentFilter.addAction(ACTION_PACKAGE_REMOVED);
            mIntentFilter.addDataScheme("package");
        }

        private void register() {
            mContext.registerReceiverForAllUsers(this, mIntentFilter, /* broadcastPermission= */
                    null, /* scheduler= */ null);
        }

        private void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            Uri data = intent.getData();
            if (data == null) {
                Slog.w(TAG, "Failed to get package name in package receiver");
                return;
            }
            String packageName = data.getSchemeSpecificPart();
            String action = intent.getAction();
            if (action == null) {
                Slog.w(TAG, "Failed to get action in package receiver");
                return;
            }
            switch (action) {
                case ACTION_PACKAGE_ADDED:
                case ACTION_PACKAGE_CHANGED:
                    addAllPackageOverrides(packageName);
                    break;
                case ACTION_PACKAGE_REMOVED:
                    if (!isInstalledForAnyUser(packageName)) {
                        removeAllPackageOverrides(packageName);
                    }
                    break;
                default:
                    Slog.w(TAG, "Unsupported action in package receiver: " + action);
                    break;
            }
        }
    };
}
