/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.Process.THREAD_PRIORITY_DEFAULT;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseSetArray;

import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.utils.WatchedArraySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class AppsFilterUtils {
    public static boolean requestsQueryAllPackages(@NonNull AndroidPackage pkg) {
        // we're not guaranteed to have permissions yet analyzed at package add, so we inspect the
        // package directly
        return pkg.getRequestedPermissions().contains(
                Manifest.permission.QUERY_ALL_PACKAGES);
    }

    /** Returns true if the querying package may query for the potential target package */
    public static boolean canQueryViaComponents(AndroidPackage querying,
            AndroidPackage potentialTarget, WatchedArraySet<String> protectedBroadcasts) {
        if (!querying.getQueriesIntents().isEmpty()) {
            for (Intent intent : querying.getQueriesIntents()) {
                if (matchesPackage(intent, potentialTarget, protectedBroadcasts)) {
                    return true;
                }
            }
        }
        if (!querying.getQueriesProviders().isEmpty()
                && matchesProviders(querying.getQueriesProviders(), potentialTarget)) {
            return true;
        }
        return false;
    }

    public static boolean canQueryViaPackage(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        return !querying.getQueriesPackages().isEmpty()
                && querying.getQueriesPackages().contains(potentialTarget.getPackageName());
    }

    public static boolean canQueryAsInstaller(PackageStateInternal querying,
            AndroidPackage potentialTarget) {
        final InstallSource installSource = querying.getInstallSource();
        if (potentialTarget.getPackageName().equals(installSource.mInstallerPackageName)) {
            return true;
        }
        if (!installSource.mIsInitiatingPackageUninstalled
                && potentialTarget.getPackageName().equals(installSource.mInitiatingPackageName)) {
            return true;
        }
        return false;
    }

    public static boolean canQueryAsUpdateOwner(PackageStateInternal querying,
            AndroidPackage potentialTarget) {
        final InstallSource installSource = querying.getInstallSource();
        if (potentialTarget.getPackageName().equals(installSource.mUpdateOwnerPackageName)) {
            return true;
        }
        return false;
    }

    public static boolean canQueryViaUsesLibrary(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        if (potentialTarget.getLibraryNames().isEmpty()) {
            return false;
        }
        final List<String> libNames = potentialTarget.getLibraryNames();
        for (int i = 0, size = libNames.size(); i < size; i++) {
            final String libName = libNames.get(i);
            if (querying.getUsesLibraries().contains(libName)
                    || querying.getUsesOptionalLibraries().contains(libName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProviders(
            Set<String> queriesAuthorities, AndroidPackage potentialTarget) {
        for (int p = ArrayUtils.size(potentialTarget.getProviders()) - 1; p >= 0; p--) {
            ParsedProvider provider = potentialTarget.getProviders().get(p);
            if (!provider.isExported()) {
                continue;
            }
            if (provider.getAuthority() == null) {
                continue;
            }
            StringTokenizer authorities = new StringTokenizer(provider.getAuthority(), ";",
                    false);
            while (authorities.hasMoreElements()) {
                if (queriesAuthorities.contains(authorities.nextToken())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesPackage(Intent intent, AndroidPackage potentialTarget,
            WatchedArraySet<String> protectedBroadcasts) {
        if (matchesAnyComponents(
                intent, potentialTarget.getServices(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getActivities(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(intent, potentialTarget.getReceivers(), protectedBroadcasts)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getProviders(), null /*protectedBroadcasts*/)) {
            return true;
        }
        return false;
    }

    private static boolean matchesAnyComponents(Intent intent,
            List<? extends ParsedMainComponent> components,
            WatchedArraySet<String> protectedBroadcasts) {
        for (int i = ArrayUtils.size(components) - 1; i >= 0; i--) {
            ParsedMainComponent component = components.get(i);
            if (!component.isExported()) {
                continue;
            }
            if (matchesAnyFilter(intent, component, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFilter(Intent intent, ParsedComponent component,
            WatchedArraySet<String> protectedBroadcasts) {
        List<ParsedIntentInfo> intents = component.getIntents();
        for (int i = ArrayUtils.size(intents) - 1; i >= 0; i--) {
            IntentFilter intentFilter = intents.get(i).getIntentFilter();
            if (matchesIntentFilter(intent, intentFilter, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIntentFilter(Intent intent, IntentFilter intentFilter,
            @Nullable WatchedArraySet<String> protectedBroadcasts) {
        return intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "AppsFilter", true,
                protectedBroadcasts != null ? protectedBroadcasts.untrackedStorage() : null) > 0;
    }

    /**
     * A helper class for parallel computing of component visibility of all packages on the device.
     */
    public static final class ParallelComputeComponentVisibility {
        private static final int MAX_THREADS = 4;

        private final ArrayMap<String, ? extends PackageStateInternal> mExistingSettings;
        private final ArraySet<Integer> mForceQueryable;
        private final WatchedArraySet<String> mProtectedBroadcasts;

        ParallelComputeComponentVisibility(
                @NonNull ArrayMap<String, ? extends PackageStateInternal> existingSettings,
                @NonNull ArraySet<Integer> forceQueryable,
                @NonNull WatchedArraySet<String> protectedBroadcasts) {
            mExistingSettings = existingSettings;
            mForceQueryable = forceQueryable;
            mProtectedBroadcasts = protectedBroadcasts;
        }

        /**
         * Computes component visibility of all packages in parallel from a thread pool.
         */
        @NonNull
        SparseSetArray<Integer> execute() {
            final SparseSetArray<Integer> queriesViaComponent = new SparseSetArray<>();
            final ExecutorService pool = ConcurrentUtils.newFixedThreadPool(
                    MAX_THREADS, ParallelComputeComponentVisibility.class.getSimpleName(),
                    THREAD_PRIORITY_DEFAULT);
            try {
                final List<Pair<PackageState, Future<ArraySet<Integer>>>> futures =
                        new ArrayList<>();
                for (int i = mExistingSettings.size() - 1; i >= 0; i--) {
                    final PackageStateInternal setting = mExistingSettings.valueAt(i);
                    final AndroidPackage pkg = setting.getPkg();
                    if (pkg == null || requestsQueryAllPackages(pkg)) {
                        continue;
                    }
                    if (pkg.getQueriesIntents().isEmpty()
                            && pkg.getQueriesProviders().isEmpty()) {
                        continue;
                    }
                    futures.add(new Pair(setting,
                            pool.submit(() -> getVisibleListOfQueryViaComponents(setting))));
                }
                for (int i = 0; i < futures.size(); i++) {
                    final int appId = futures.get(i).first.getAppId();
                    final Future<ArraySet<Integer>> future = futures.get(i).second;
                    try {
                        final ArraySet<Integer> visibleList = future.get();
                        if (visibleList.size() != 0) {
                            queriesViaComponent.addAll(appId, visibleList);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        throw new IllegalStateException(e);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            return queriesViaComponent;
        }

        /**
         * Returns a set of app IDs that contains components resolved by the queries intent
         * or provider that declared in the manifest of the querying package.
         *
         * @param setting The package to query.
         * @return A set of app IDs.
         */
        @NonNull
        private ArraySet<Integer> getVisibleListOfQueryViaComponents(
                @NonNull PackageStateInternal setting) {
            final ArraySet<Integer> result = new ArraySet();
            for (int i = mExistingSettings.size() - 1; i >= 0; i--) {
                final PackageStateInternal otherSetting = mExistingSettings.valueAt(i);
                if (setting.getAppId() == otherSetting.getAppId()) {
                    continue;
                }
                if (otherSetting.getPkg() == null || mForceQueryable.contains(
                        otherSetting.getAppId())) {
                    continue;
                }
                final boolean canQuery = canQueryViaComponents(
                        setting.getPkg(), otherSetting.getPkg(), mProtectedBroadcasts);
                if (canQuery) {
                    result.add(otherSetting.getAppId());
                }
            }
            return result;
        }
    }
}
