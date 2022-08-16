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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.utils.WatchedArrayList;

import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

final class AppsFilterUtils {
    public static boolean requestsQueryAllPackages(@NonNull AndroidPackage pkg) {
        // we're not guaranteed to have permissions yet analyzed at package add, so we inspect the
        // package directly
        return pkg.getRequestedPermissions().contains(
                Manifest.permission.QUERY_ALL_PACKAGES);
    }

    /** Returns true if the querying package may query for the potential target package */
    public static boolean canQueryViaComponents(AndroidPackage querying,
            AndroidPackage potentialTarget, WatchedArrayList<String> protectedBroadcasts) {
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
        if (potentialTarget.getPackageName().equals(installSource.installerPackageName)) {
            return true;
        }
        if (!installSource.isInitiatingPackageUninstalled
                && potentialTarget.getPackageName().equals(installSource.initiatingPackageName)) {
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
            WatchedArrayList<String> protectedBroadcasts) {
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
            WatchedArrayList<String> protectedBroadcasts) {
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
            WatchedArrayList<String> protectedBroadcasts) {
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
            @Nullable WatchedArrayList<String> protectedBroadcasts) {
        return intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "AppsFilter", true,
                protectedBroadcasts != null ? protectedBroadcasts.untrackedStorage() : null) > 0;
    }
}
