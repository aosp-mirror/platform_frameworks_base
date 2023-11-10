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

package com.android.server.pm.resolution;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.server.pm.Computer;
import com.android.server.pm.DumpState;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.utils.WatchableImpl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public abstract class ComponentResolverBase extends WatchableImpl implements ComponentResolverApi {

    @NonNull
    protected ComponentResolver.ActivityIntentResolver mActivities;

    @NonNull
    protected ComponentResolver.ProviderIntentResolver mProviders;

    @NonNull
    protected ComponentResolver.ReceiverIntentResolver mReceivers;

    @NonNull
    protected ComponentResolver.ServiceIntentResolver mServices;

    /** Mapping from provider authority [first directory in content URI codePath) to provider. */
    @NonNull
    protected ArrayMap<String, ParsedProvider> mProvidersByAuthority;

    @NonNull
    protected final UserManagerService mUserManager;

    protected ComponentResolverBase(@NonNull UserManagerService userManager) {
        mUserManager = userManager;
    }

    @Override
    public boolean componentExists(@NonNull ComponentName componentName) {
        ParsedMainComponent component = mActivities.mActivities.get(componentName);
        if (component != null) {
            return true;
        }
        component = mReceivers.mActivities.get(componentName);
        if (component != null) {
            return true;
        }
        component = mServices.mServices.get(componentName);
        if (component != null) {
            return true;
        }
        return mProviders.mProviders.get(componentName) != null;
    }

    @Nullable
    @Override
    public ParsedActivity getActivity(@NonNull ComponentName component) {
        return mActivities.mActivities.get(component);
    }

    @Nullable
    @Override
    public ParsedProvider getProvider(@NonNull ComponentName component) {
        return mProviders.mProviders.get(component);
    }

    @Nullable
    @Override
    public ParsedActivity getReceiver(@NonNull ComponentName component) {
        return mReceivers.mActivities.get(component);
    }

    @Nullable
    @Override
    public ParsedService getService(@NonNull ComponentName component) {
        return mServices.mServices.get(component);
    }

    /**
     * Returns {@code true} if the given activity is defined by some package
     */
    @Override
    public boolean isActivityDefined(@NonNull ComponentName component) {
        return mActivities.mActivities.get(component) != null;
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, int userId) {
        return mActivities.queryIntent(computer, intent, resolvedType, flags, userId);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryActivities(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> activities,
            int userId) {
        return mActivities.queryIntentForPackage(computer, intent, resolvedType, flags, activities,
                userId);
    }

    @Nullable
    @Override
    public ProviderInfo queryProvider(@NonNull Computer computer, @NonNull String authority,
            long flags, int userId) {
        final ParsedProvider p = mProvidersByAuthority.get(authority);
        if (p == null) {
            return null;
        }
        PackageStateInternal packageState = computer.getPackageStateInternal(p.getPackageName());
        if (packageState == null) {
            return null;
        }
        final AndroidPackage pkg = packageState.getPkg();
        if (pkg == null) {
            return null;
        }
        final PackageUserStateInternal state = packageState.getUserStateOrDefault(userId);
        ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(
                pkg, flags, state, userId, packageState);
        if (appInfo == null) {
            return null;
        }
        return PackageInfoUtils.generateProviderInfo(pkg, p, flags, state, appInfo, userId,
                packageState);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, int userId) {
        return mProviders.queryIntent(computer, intent, resolvedType, flags, userId);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryProviders(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedProvider> providers,
            @UserIdInt int userId) {
        return mProviders.queryIntentForPackage(computer, intent, resolvedType, flags, providers,
                userId);
    }

    @Nullable
    @Override
    public List<ProviderInfo> queryProviders(@NonNull Computer computer,
            @Nullable String processName, @Nullable String metaDataKey, int uid, long flags,
            int userId) {
        if (!mUserManager.exists(userId)) {
            return null;
        }
        List<ProviderInfo> providerList = null;
        PackageInfoUtils.CachedApplicationInfoGenerator appInfoGenerator = null;
        for (int i = mProviders.mProviders.size() - 1; i >= 0; --i) {
            final ParsedProvider p = mProviders.mProviders.valueAt(i);
            if (p.getAuthority() == null) {
                continue;
            }

            final PackageStateInternal ps = computer.getPackageStateInternal(p.getPackageName());
            if (ps == null) {
                continue;
            }

            AndroidPackage pkg = ps.getPkg();
            if (pkg == null) {
                continue;
            }

            if (processName != null && (!p.getProcessName().equals(processName)
                    || !UserHandle.isSameApp(pkg.getUid(), uid))) {
                continue;
            }
            // See PM.queryContentProviders()'s javadoc for why we have the metaData parameter.
            if (metaDataKey != null && !p.getMetaData().containsKey(metaDataKey)) {
                continue;
            }
            if (appInfoGenerator == null) {
                appInfoGenerator = new PackageInfoUtils.CachedApplicationInfoGenerator();
            }
            final PackageUserStateInternal state = ps.getUserStateOrDefault(userId);
            final ApplicationInfo appInfo =
                    appInfoGenerator.generate(pkg, flags, state, userId, ps);
            if (appInfo == null) {
                continue;
            }

            final ProviderInfo info = PackageInfoUtils.generateProviderInfo(
                    pkg, p, flags, state, appInfo, userId, ps);
            if (info == null) {
                continue;
            }
            if (providerList == null) {
                providerList = new ArrayList<>(i + 1);
            }
            providerList.add(info);
        }
        return providerList;
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, int userId) {
        return mReceivers.queryIntent(computer, intent, resolvedType, flags, userId);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryReceivers(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedActivity> receivers,
            @UserIdInt int userId) {
        return mReceivers.queryIntentForPackage(computer, intent, resolvedType, flags, receivers,
                userId);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @UserIdInt int userId) {
        return mServices.queryIntent(computer, intent, resolvedType, flags, userId);
    }

    @Nullable
    @Override
    public List<ResolveInfo> queryServices(@NonNull Computer computer, @NonNull Intent intent,
            @Nullable String resolvedType, long flags, @NonNull List<ParsedService> services,
            @UserIdInt int userId) {
        return mServices.queryIntentForPackage(computer, intent, resolvedType, flags, services,
                userId);
    }

    @Override
    public void querySyncProviders(@NonNull Computer computer, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo, boolean safeMode, int userId) {
        PackageInfoUtils.CachedApplicationInfoGenerator appInfoGenerator = null;
        for (int i = mProvidersByAuthority.size() - 1; i >= 0; --i) {
            final ParsedProvider p = mProvidersByAuthority.valueAt(i);
            if (!p.isSyncable()) {
                continue;
            }

            final PackageStateInternal ps = computer.getPackageStateInternal(p.getPackageName());
            if (ps == null) {
                continue;
            }

            final AndroidPackage pkg = ps.getPkg();
            if (pkg == null) {
                continue;
            }

            if (safeMode && !ps.isSystem()) {
                continue;
            }
            if (appInfoGenerator == null) {
                appInfoGenerator = new PackageInfoUtils.CachedApplicationInfoGenerator();
            }
            final PackageUserStateInternal state = ps.getUserStateOrDefault(userId);
            final ApplicationInfo appInfo =
                    appInfoGenerator.generate(pkg, 0, state, userId, ps);
            if (appInfo == null) {
                continue;
            }

            final ProviderInfo info = PackageInfoUtils.generateProviderInfo(
                    pkg, p, 0, state, appInfo, userId, ps);
            if (info == null) {
                continue;
            }
            outNames.add(mProvidersByAuthority.keyAt(i));
            outInfo.add(info);
        }
    }

    @Override
    public void dumpActivityResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        if (mActivities.dump(pw, dumpState.getTitlePrinted() ? "\nActivity Resolver Table:"
                        : "Activity Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    @Override
    public void dumpProviderResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        if (mProviders.dump(pw, dumpState.getTitlePrinted() ? "\nProvider Resolver Table:"
                        : "Provider Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    @Override
    public void dumpReceiverResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        if (mReceivers.dump(pw, dumpState.getTitlePrinted() ? "\nReceiver Resolver Table:"
                        : "Receiver Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    @Override
    public void dumpServiceResolvers(@NonNull PrintWriter pw, @NonNull DumpState dumpState,
            @NonNull String packageName) {
        if (mServices.dump(pw, dumpState.getTitlePrinted() ? "\nService Resolver Table:"
                        : "Service Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    @Override
    public void dumpContentProviders(@NonNull Computer computer, @NonNull PrintWriter pw,
            @NonNull DumpState dumpState, @NonNull String packageName) {
        boolean printedSomething = false;
        for (ParsedProvider p : mProviders.mProviders.values()) {
            if (packageName != null && !packageName.equals(p.getPackageName())) {
                continue;
            }
            if (!printedSomething) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("Registered ContentProviders:");
                printedSomething = true;
            }
            pw.print("  ");
            ComponentName.printShortString(pw, p.getPackageName(), p.getName());
            pw.println(":");
            pw.print("    ");
            pw.println(p.toString());
        }
        printedSomething = false;
        for (Map.Entry<String, ParsedProvider> entry :
                mProvidersByAuthority.entrySet()) {
            ParsedProvider p = entry.getValue();
            if (packageName != null && !packageName.equals(p.getPackageName())) {
                continue;
            }
            if (!printedSomething) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("ContentProvider Authorities:");
                printedSomething = true;
            }
            pw.print("  [");
            pw.print(entry.getKey());
            pw.println("]:");
            pw.print("    ");
            pw.println(p.toString());

            AndroidPackage pkg = computer.getPackage(p.getPackageName());

            if (pkg != null) {
                pw.print("      applicationInfo=");
                pw.println(AndroidPackageUtils.generateAppInfoWithoutState(pkg));
            }
        }
    }

    @Override
    public void dumpServicePermissions(@NonNull PrintWriter pw, @NonNull DumpState dumpState) {
        if (dumpState.onTitlePrinted()) pw.println();
        pw.println("Service permissions:");

        final Iterator<Pair<ParsedService, ParsedIntentInfo>> filterIterator =
                mServices.filterIterator();
        while (filterIterator.hasNext()) {
            final Pair<ParsedService, ParsedIntentInfo> pair = filterIterator.next();
            ParsedService service = pair.first;

            final String permission = service.getPermission();
            if (permission != null) {
                pw.print("    ");
                pw.print(service.getComponentName().flattenToShortString());
                pw.print(": ");
                pw.println(permission);
            }
        }
    }

}
