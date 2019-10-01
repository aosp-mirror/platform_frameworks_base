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

package com.android.server.pm;

import static android.content.pm.PackageParser.Component;
import static android.content.pm.PackageParser.IntentInfo;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import android.Manifest;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.permission.IPermissionManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.FgThread;
import com.android.server.compat.PlatformCompat;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The entity responsible for filtering visibility between apps based on declarations in their
 * manifests.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class AppsFilter {

    private static final String TAG = PackageManagerService.TAG;

    // Logs all filtering instead of enforcing
    private static final boolean DEBUG_ALLOW_ALL = false;

    @SuppressWarnings("ConstantExpression")
    private static final boolean DEBUG_LOGGING = false | DEBUG_ALLOW_ALL;

    /**
     * This contains a list of packages that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    private final SparseArray<HashMap<String, Set<String>>> mImplicitlyQueryable =
            new SparseArray<>();

    /**
     * A mapping from the set of packages that query other packages via package name to the
     * list of packages that they can see.
     */
    private final HashMap<String, Set<String>> mQueriesViaPackage = new HashMap<>();

    /**
     * A mapping from the set of packages that query others via intent to the list
     * of packages that the intents resolve to.
     */
    private final HashMap<String, Set<String>> mQueriesViaIntent = new HashMap<>();

    /**
     * A set of packages that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final HashSet<String> mForceQueryable;
    /**
     * A set of packages that are always queryable by any package, regardless of their manifest
     * content.
     */
    private final Set<String> mForceQueryableByDevice;

    /** True if all system apps should be made queryable by default. */
    private final boolean mSystemAppsQueryable;

    private final IPermissionManager mPermissionManager;

    private final FeatureConfig mFeatureConfig;

    AppsFilter(FeatureConfig featureConfig, IPermissionManager permissionManager,
            String[] forceQueryableWhitelist, boolean systemAppsQueryable) {
        mFeatureConfig = featureConfig;
        final HashSet<String> forceQueryableByDeviceSet = new HashSet<>();
        Collections.addAll(forceQueryableByDeviceSet, forceQueryableWhitelist);
        this.mForceQueryableByDevice = Collections.unmodifiableSet(forceQueryableByDeviceSet);
        this.mForceQueryable = new HashSet<>();
        mPermissionManager = permissionManager;
        mSystemAppsQueryable = systemAppsQueryable;
    }

    public interface FeatureConfig {
        /** Called when the system is ready and components can be queried. */
        void onSystemReady();

        /** @return true if we should filter apps at all. */
        boolean isGloballyEnabled();

        /** @return true if the feature is enabled for the given package. */
        boolean packageIsEnabled(PackageParser.Package pkg);

    }

    private static class FeatureConfigImpl implements FeatureConfig {
        private static final String FILTERING_ENABLED_NAME = "package_query_filtering_enabled";
        private final PackageManagerService.Injector mInjector;
        private volatile boolean mFeatureEnabled = true;

        private FeatureConfigImpl(PackageManagerService.Injector injector) {
            mInjector = injector;
        }

        @Override
        public void onSystemReady() {
            mFeatureEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FILTERING_ENABLED_NAME,
                    true);
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FgThread.getExecutor(),
                    properties -> {
                        synchronized (FeatureConfigImpl.this) {
                            mFeatureEnabled = properties.getBoolean(
                                    FILTERING_ENABLED_NAME, true);
                        }
                    });
        }

        @Override
        public boolean isGloballyEnabled() {
            return mFeatureEnabled;
        }

        @Override
        public boolean packageIsEnabled(PackageParser.Package pkg) {
            final PlatformCompat compatibility = mInjector.getCompatibility();
            if (compatibility == null) {
                Slog.wtf(TAG, "PlatformCompat is null");
                return mFeatureEnabled;
            }
            return compatibility.isChangeEnabled(
                    PackageManager.FILTER_APPLICATION_QUERY, pkg.applicationInfo);
        }
    }


    public static AppsFilter create(PackageManagerService.Injector injector) {
        final boolean forceSystemAppsQueryable =
                injector.getContext().getResources()
                        .getBoolean(R.bool.config_forceSystemPackagesQueryable);
        final FeatureConfig featureConfig = new FeatureConfigImpl(injector);
        final String[] forcedQueryablePackageNames;
        if (forceSystemAppsQueryable) {
            // all system apps already queryable, no need to read and parse individual exceptions
            forcedQueryablePackageNames = new String[]{};
        } else {
            forcedQueryablePackageNames =
                    injector.getContext().getResources()
                            .getStringArray(R.array.config_forceQueryablePackages);
            for (int i = 0; i < forcedQueryablePackageNames.length; i++) {
                forcedQueryablePackageNames[i] = forcedQueryablePackageNames[i].intern();
            }
        }
        IPermissionManager permissionmgr =
                (IPermissionManager) ServiceManager.getService("permissionmgr");

        return new AppsFilter(featureConfig, permissionmgr, forcedQueryablePackageNames,
                forceSystemAppsQueryable);
    }

    /** Returns true if the querying package may query for the potential target package */
    private static boolean canQuery(PackageParser.Package querying,
            PackageParser.Package potentialTarget) {
        if (querying.mQueriesIntents == null) {
            return false;
        }
        for (Intent intent : querying.mQueriesIntents) {
            if (matches(intent, potentialTarget.providers, potentialTarget.activities,
                    potentialTarget.services, potentialTarget.receivers)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Intent intent,
            ArrayList<PackageParser.Provider> providerList,
            ArrayList<? extends Component<? extends IntentInfo>>... componentLists) {
        for (int p = providerList.size() - 1; p >= 0; p--) {
            PackageParser.Provider provider = providerList.get(p);
            final ProviderInfo providerInfo = provider.info;
            final Uri data = intent.getData();
            if ("content".equalsIgnoreCase(intent.getScheme())
                    && data != null
                    && providerInfo.authority.equalsIgnoreCase(data.getAuthority())) {
                return true;
            }
        }

        for (int l = componentLists.length - 1; l >= 0; l--) {
            ArrayList<? extends Component<? extends IntentInfo>> components = componentLists[l];
            for (int c = components.size() - 1; c >= 0; c--) {
                Component<? extends IntentInfo> component = components.get(c);
                ArrayList<? extends IntentInfo> intents = component.intents;
                for (int i = intents.size() - 1; i >= 0; i--) {
                    IntentFilter intentFilter = intents.get(i);
                    if (intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                            intent.getData(), intent.getCategories(), "AppsFilter") > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Grants access based on an interaction between a calling and target package, granting
     * visibility of the caller from the target.
     *
     * @param callingPackage    the package initiating the interaction
     * @param targetPackage     the package being interacted with and thus gaining visibility of the
     *                          initiating package.
     * @param userId            the user in which this interaction was taking place
     */
    public void grantImplicitAccess(
            String callingPackage, String targetPackage, int userId) {
        HashMap<String, Set<String>> currentUser = mImplicitlyQueryable.get(userId);
        if (currentUser == null) {
            currentUser = new HashMap<>();
            mImplicitlyQueryable.put(userId, currentUser);
        }
        if (!currentUser.containsKey(targetPackage)) {
            currentUser.put(targetPackage, new HashSet<>());
        }
        currentUser.get(targetPackage).add(callingPackage);
    }

    public void onSystemReady() {
        mFeatureConfig.onSystemReady();
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkg   the new package being added
     * @param existing all other packages currently on the device.
     */
    public void addPackage(PackageParser.Package newPkg,
            Map<String, PackageParser.Package> existing) {
        // let's re-evaluate the ability of already added packages to see this new package
        if (newPkg.mForceQueryable
                || (mSystemAppsQueryable && (newPkg.isSystem() || newPkg.isUpdatedSystemApp()))) {
            mForceQueryable.add(newPkg.packageName);
        } else {
            for (String packageName : mQueriesViaIntent.keySet()) {
                if (packageName == newPkg.packageName) {
                    continue;
                }
                final PackageParser.Package existingPackage = existing.get(packageName);
                if (canQuery(existingPackage, newPkg)) {
                    mQueriesViaIntent.get(packageName).add(newPkg.packageName);
                }
            }
        }
        // if the new package declares them, let's evaluate its ability to see existing packages
        mQueriesViaIntent.put(newPkg.packageName, new HashSet<>());
        for (PackageParser.Package existingPackage : existing.values()) {
            if (existingPackage.packageName == newPkg.packageName) {
                continue;
            }
            if (existingPackage.mForceQueryable
                    || (mSystemAppsQueryable
                    && (newPkg.isSystem() || newPkg.isUpdatedSystemApp()))) {
                continue;
            }
            if (canQuery(newPkg, existingPackage)) {
                mQueriesViaIntent.get(newPkg.packageName).add(existingPackage.packageName);
            }
        }
        final HashSet<String> queriesPackages = new HashSet<>(
                newPkg.mQueriesPackages == null ? 0 : newPkg.mQueriesPackages.size());
        if (newPkg.mQueriesPackages != null) {
            queriesPackages.addAll(newPkg.mQueriesPackages);
        }
        mQueriesViaPackage.put(newPkg.packageName, queriesPackages);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param packageName the name of the package being removed.
     */
    public void removePackage(String packageName) {
        mForceQueryable.remove(packageName);

        for (int i = 0; i < mImplicitlyQueryable.size(); i++) {
            mImplicitlyQueryable.valueAt(i).remove(packageName);
            for (Set<String> initiators : mImplicitlyQueryable.valueAt(i).values()) {
                initiators.remove(packageName);
            }
        }

        mQueriesViaIntent.remove(packageName);
        for (Set<String> declarators : mQueriesViaIntent.values()) {
            declarators.remove(packageName);
        }

        mQueriesViaPackage.remove(packageName);
    }

    /**
     * Returns true if the calling package should not be able to see the target package, false if no
     * filtering should be done.
     *
     * @param callingUid       the uid of the caller attempting to access a package
     * @param callingSetting   the setting attempting to access a package or null if it could not be
     *                         found
     * @param targetPkgSetting the package being accessed
     * @param userId           the user in which this access is being attempted
     */
    public boolean shouldFilterApplication(int callingUid, @Nullable SettingBase callingSetting,
            PackageSetting targetPkgSetting, int userId) {
        final boolean featureEnabled = mFeatureConfig.isGloballyEnabled();
        if (!featureEnabled) {
            if (DEBUG_LOGGING) {
                Slog.d(TAG, "filtering disabled; skipped");
            }
            return false;
        }
        if (callingUid < Process.FIRST_APPLICATION_UID) {
            if (DEBUG_LOGGING) {
                Slog.d(TAG, "filtering skipped; " + callingUid + " is system");
            }
            return false;
        }
        if (callingSetting == null) {
            Slog.wtf(TAG, "No setting found for non system uid " + callingUid);
            return true;
        }
        PackageSetting callingPkgSetting = null;
        if (callingSetting instanceof PackageSetting) {
            callingPkgSetting = (PackageSetting) callingSetting;
            if (!shouldFilterApplicationInternal(callingPkgSetting, targetPkgSetting,
                    userId)) {
                return false;
            }
        } else if (callingSetting instanceof SharedUserSetting) {
            final ArraySet<PackageSetting> packageSettings =
                    ((SharedUserSetting) callingSetting).packages;
            if (packageSettings != null && packageSettings.size() > 0) {
                for (int i = 0, max = packageSettings.size(); i < max; i++) {
                    final PackageSetting packageSetting = packageSettings.valueAt(i);
                    if (!shouldFilterApplicationInternal(packageSetting, targetPkgSetting,
                            userId)) {
                        return false;
                    }
                    if (callingPkgSetting == null && packageSetting.pkg != null) {
                        callingPkgSetting = packageSetting;
                    }
                }
                if (callingPkgSetting == null) {
                    Slog.wtf(TAG, callingSetting + " does not have any non-null packages!");
                    return true;
                }
            } else {
                Slog.wtf(TAG, callingSetting + " has no packages!");
                return true;
            }
        }

        if (DEBUG_LOGGING) {
            log(callingPkgSetting, targetPkgSetting,
                    DEBUG_ALLOW_ALL ? "ALLOWED" : "BLOCKED");
        }
        return !DEBUG_ALLOW_ALL;
    }

    private boolean shouldFilterApplicationInternal(
            PackageSetting callingPkgSetting, PackageSetting targetPkgSetting, int userId) {
        final String callingName = callingPkgSetting.pkg.packageName;
        final PackageParser.Package targetPkg = targetPkgSetting.pkg;

        if (!mFeatureConfig.packageIsEnabled(callingPkgSetting.pkg)) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "DISABLED");
            }
            return false;
        }
        // This package isn't technically installed and won't be written to settings, so we can
        // treat it as filtered until it's available again.
        if (targetPkg == null) {
            if (DEBUG_LOGGING) {
                Slog.wtf(TAG, "shouldFilterApplication: " + "targetPkg is null");
            }
            return true;
        }
        final String targetName = targetPkg.packageName;
        if (callingPkgSetting.pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.R) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "caller pre-R");
            }
            return false;
        }
        if (isImplicitlyQueryableSystemApp(targetPkgSetting)) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "implicitly queryable sys");
            }
            return false;
        }
        if (targetPkg.mForceQueryable) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "manifest forceQueryable");
            }
            return false;
        }
        if (mForceQueryable.contains(targetName)) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "whitelist forceQueryable");
            }
            return false;
        }
        if (mQueriesViaPackage.containsKey(callingName)
                && mQueriesViaPackage.get(callingName).contains(
                targetName)) {
            // the calling package has explicitly declared the target package; allow
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "queries package");
            }
            return false;
        } else if (mQueriesViaIntent.containsKey(callingName)
                && mQueriesViaIntent.get(callingName).contains(targetName)) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "queries intent");
            }
            return false;
        }
        if (mImplicitlyQueryable.get(userId) != null
                && mImplicitlyQueryable.get(userId).containsKey(callingName)
                && mImplicitlyQueryable.get(userId).get(callingName).contains(targetName)) {
            if (DEBUG_LOGGING) {
                log(callingPkgSetting, targetPkgSetting, "implicitly queryable for user");
            }
            return false;
        }
        if (callingPkgSetting.pkg.instrumentation.size() > 0) {
            for (int i = 0, max = callingPkgSetting.pkg.instrumentation.size(); i < max; i++) {
                if (callingPkgSetting.pkg.instrumentation.get(i).info.targetPackage == targetName) {
                    if (DEBUG_LOGGING) {
                        log(callingPkgSetting, targetPkgSetting, "instrumentation");
                    }
                    return false;
                }
            }
        }
        try {
            if (mPermissionManager.checkPermission(
                    Manifest.permission.QUERY_ALL_PACKAGES, callingName, userId)
                    == PackageManager.PERMISSION_GRANTED) {
                if (DEBUG_LOGGING) {
                    log(callingPkgSetting, targetPkgSetting, "permission");
                }
                return false;
            }
        } catch (RemoteException e) {
            return true;
        }
        return true;
    }

    private static void log(PackageSetting callingPkgSetting, PackageSetting targetPkgSetting,
            String description) {
        Slog.wtf(TAG,
                "interaction: " + callingPkgSetting.name + " -> " + targetPkgSetting.name + " "
                        + description);
    }

    private boolean isImplicitlyQueryableSystemApp(PackageSetting targetPkgSetting) {
        return targetPkgSetting.isSystem() && (mSystemAppsQueryable
                || mForceQueryableByDevice.contains(targetPkgSetting.pkg.packageName));
    }

    public void dumpQueries(
            PrintWriter pw, @Nullable String filteringPackageName, DumpState dumpState,
            int[] users) {
        pw.println();
        pw.println("Queries:");
        dumpState.onTitlePrinted();
        pw.println("  system apps queryable: " + mSystemAppsQueryable);
        dumpPackageSet(pw, filteringPackageName, mForceQueryableByDevice, "System whitelist", "  ");
        dumpPackageSet(pw, filteringPackageName, mForceQueryable, "forceQueryable", "  ");
        pw.println("  queries via package name:");
        dumpQueriesMap(pw, filteringPackageName, mQueriesViaPackage, "    ");
        pw.println("  queries via intent:");
        dumpQueriesMap(pw, filteringPackageName, mQueriesViaIntent, "    ");
        pw.println("  queryable via interaction:");
        for (int user : users) {
            pw.append("    User ").append(Integer.toString(user)).println(":");
            final HashMap<String, Set<String>> queryMapForUser = mImplicitlyQueryable.get(user);
            if (queryMapForUser != null) {
                dumpQueriesMap(pw, filteringPackageName, queryMapForUser, "      ");
            }
        }
    }

    private static void dumpQueriesMap(PrintWriter pw, @Nullable String filteringPackageName,
            HashMap<String, Set<String>> queriesMap, String spacing) {
        for (String callingPkg : queriesMap.keySet()) {
            if (Objects.equals(callingPkg, filteringPackageName)) {
                // don't filter target package names if the calling is filteringPackageName
                dumpPackageSet(pw, null /*filteringPackageName*/, queriesMap.get(callingPkg),
                        callingPkg, spacing);
            } else {
                dumpPackageSet(pw, filteringPackageName, queriesMap.get(callingPkg), callingPkg,
                        spacing);
            }
        }
    }

    private static void dumpPackageSet(PrintWriter pw, @Nullable String filteringPackageName,
            Set<String> targetPkgSet, String subTitle, String spacing) {
        if (targetPkgSet != null && targetPkgSet.size() > 0
                && (filteringPackageName == null || targetPkgSet.contains(filteringPackageName))) {
            pw.append(spacing).append(subTitle).println(":");
            for (String pkgName : targetPkgSet) {
                if (filteringPackageName == null || Objects.equals(filteringPackageName, pkgName)) {
                    pw.append(spacing).append("  ").println(pkgName);
                }
            }
        }
    }
}
