/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER;
import static android.content.pm.PackageManagerInternal.PACKAGE_SETUP_WIZARD;

import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageUserState;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.IntentResolver;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageInfoUtils.CachedApplicationInfoGenerator;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.WatchableImpl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Resolves all Android component types [activities, services, providers and receivers]. */
public class ComponentResolver
        extends WatchableImpl
        implements Snappable {
    private static final boolean DEBUG = false;
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG_FILTERS = false;
    private static final boolean DEBUG_SHOW_INFO = false;

    // Convenience function to report that this object has changed.
    private void onChanged() {
        dispatchChange(this);
    }

    /**
     * The set of all protected actions [i.e. those actions for which a high priority
     * intent filter is disallowed].
     */
    private static final Set<String> PROTECTED_ACTIONS = new ArraySet<>();
    static {
        PROTECTED_ACTIONS.add(Intent.ACTION_SEND);
        PROTECTED_ACTIONS.add(Intent.ACTION_SENDTO);
        PROTECTED_ACTIONS.add(Intent.ACTION_SEND_MULTIPLE);
        PROTECTED_ACTIONS.add(Intent.ACTION_VIEW);
    }

    static final Comparator<ResolveInfo> RESOLVE_PRIORITY_SORTER = (r1, r2) -> {
        int v1 = r1.priority;
        int v2 = r2.priority;
        //System.out.println("Comparing: q1=" + q1 + " q2=" + q2);
        if (v1 != v2) {
            return (v1 > v2) ? -1 : 1;
        }
        v1 = r1.preferredOrder;
        v2 = r2.preferredOrder;
        if (v1 != v2) {
            return (v1 > v2) ? -1 : 1;
        }
        if (r1.isDefault != r2.isDefault) {
            return r1.isDefault ? -1 : 1;
        }
        v1 = r1.match;
        v2 = r2.match;
        //System.out.println("Comparing: m1=" + m1 + " m2=" + m2);
        if (v1 != v2) {
            return (v1 > v2) ? -1 : 1;
        }
        if (r1.system != r2.system) {
            return r1.system ? -1 : 1;
        }
        if (r1.activityInfo != null) {
            return r1.activityInfo.packageName.compareTo(r2.activityInfo.packageName);
        }
        if (r1.serviceInfo != null) {
            return r1.serviceInfo.packageName.compareTo(r2.serviceInfo.packageName);
        }
        if (r1.providerInfo != null) {
            return r1.providerInfo.packageName.compareTo(r2.providerInfo.packageName);
        }
        return 0;
    };

    private static UserManagerService sUserManager;
    private static PackageManagerInternal sPackageManagerInternal;

    /**
     * Locking within package manager is going to get worse before it gets better. Currently,
     * we need to share the {@link PackageManagerService} lock to prevent deadlocks. This occurs
     * because in order to safely query the resolvers, we need to obtain this lock. However,
     * during resolution, we call into the {@link PackageManagerService}. This is _not_ to
     * operate on data controlled by the service proper, but, to check the state of package
     * settings [contained in a {@link Settings} object]. However, the {@link Settings} object
     * happens to be protected by the main {@link PackageManagerService} lock.
     * <p>
     * There are a couple potential solutions.
     * <ol>
     * <li>Split all of our locks into reader/writer locks. This would allow multiple,
     * simultaneous read operations and means we don't have to be as cautious about lock
     * layering. Only when we want to perform a write operation will we ever be in a
     * position to deadlock the system.</li>
     * <li>Use the same lock across all classes within the {@code com.android.server.pm}
     * package. By unifying the lock object, we remove any potential lock layering issues
     * within the package manager. However, we already have a sense that this lock is
     * heavily contended and merely adding more dependencies on it will have further
     * impact.</li>
     * <li>Implement proper lock ordering within the package manager. By defining the
     * relative layer of the component [eg. {@link PackageManagerService} is at the top.
     * Somewhere in the middle would be {@link ComponentResolver}. At the very bottom
     * would be {@link Settings}.] The ordering would allow higher layers to hold their
     * lock while calling down. Lower layers must relinquish their lock before calling up.
     * Since {@link Settings} would live at the lowest layer, the {@link ComponentResolver}
     * would be able to hold its lock while checking the package setting state.</li>
     * </ol>
     */
    private final PackageManagerTracedLock mLock;

    /** All available activities, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ActivityIntentResolver mActivities;

    /** All available providers, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ProviderIntentResolver mProviders;

    /** All available receivers, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ReceiverIntentResolver mReceivers;

    /** All available services, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ServiceIntentResolver mServices;

    /** Mapping from provider authority [first directory in content URI codePath) to provider. */
    @GuardedBy("mLock")
    private final ArrayMap<String, ParsedProvider> mProvidersByAuthority;

    /** Whether or not processing protected filters should be deferred. */
    private boolean mDeferProtectedFilters = true;

    /**
     * Tracks high priority intent filters for protected actions. During boot, certain
     * filter actions are protected and should never be allowed to have a high priority
     * intent filter for them. However, there is one, and only one exception -- the
     * setup wizard. It must be able to define a high priority intent filter for these
     * actions to ensure there are no escapes from the wizard. We need to delay processing
     * of these during boot as we need to inspect at all of the intent filters on the
     * /system partition in order to know which component is the setup wizard. This can
     * only ever be non-empty if {@link #mDeferProtectedFilters} is {@code true}.
     *
     * This is a pair of component package name to actual filter, because we don't store the
     * name inside the filter. It's technically independent of the component it's contained in.
     */
    private List<Pair<ParsedMainComponent, ParsedIntentInfo>> mProtectedFilters;

    ComponentResolver(UserManagerService userManager,
            PackageManagerInternal packageManagerInternal,
            PackageManagerTracedLock lock) {
        sPackageManagerInternal = packageManagerInternal;
        sUserManager = userManager;
        mLock = lock;

        mActivities = new ActivityIntentResolver();
        mProviders = new ProviderIntentResolver();
        mReceivers = new ReceiverIntentResolver();
        mServices = new ServiceIntentResolver();
        mProvidersByAuthority = new ArrayMap<>();
        mDeferProtectedFilters = true;

        mSnapshot = new SnapshotCache<ComponentResolver>(this, this) {
                @Override
                public ComponentResolver createSnapshot() {
                    return new ComponentResolver(mSource);
                }};
    }

    // Copy constructor used in creating snapshots.
    private ComponentResolver(ComponentResolver orig) {
        // Do not set the static variables that are set in the default constructor.   Do
        // create a new object for the lock.  The snapshot is read-only, so a lock is not
        // strictly required.  However, the current code is simpler if the lock exists,
        // but does not contend with any outside class.
        // TODO: make the snapshot lock-free
        mLock = new PackageManagerTracedLock();

        mActivities = new ActivityIntentResolver(orig.mActivities);
        mProviders = new ProviderIntentResolver(orig.mProviders);
        mReceivers = new ReceiverIntentResolver(orig.mReceivers);
        mServices = new ServiceIntentResolver(orig.mServices);
        mProvidersByAuthority = new ArrayMap<>(orig.mProvidersByAuthority);
        mDeferProtectedFilters = orig.mDeferProtectedFilters;
        mProtectedFilters = (mProtectedFilters == null)
                            ? null
                            : new ArrayList<>(orig.mProtectedFilters);

        mSnapshot = null;
    }

    final SnapshotCache<ComponentResolver> mSnapshot;

    /**
     * Create a snapshot.
     */
    public ComponentResolver snapshot() {
        return mSnapshot.snapshot();
    }


    /** Returns the given activity */
    @Nullable
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public ParsedActivity getActivity(@NonNull ComponentName component) {
        synchronized (mLock) {
            return mActivities.mActivities.get(component);
        }
    }

    /** Returns the given provider */
    @Nullable
    ParsedProvider getProvider(@NonNull ComponentName component) {
        synchronized (mLock) {
            return mProviders.mProviders.get(component);
        }
    }

    /** Returns the given receiver */
    @Nullable
    ParsedActivity getReceiver(@NonNull ComponentName component) {
        synchronized (mLock) {
            return mReceivers.mActivities.get(component);
        }
    }

    /** Returns the given service */
    @Nullable
    ParsedService getService(@NonNull ComponentName component) {
        synchronized (mLock) {
            return mServices.mServices.get(component);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean componentExists(@NonNull ComponentName componentName) {
        synchronized (mLock) {
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
    }

    @Nullable
    List<ResolveInfo> queryActivities(Intent intent, String resolvedType, int flags,
            int userId) {
        synchronized (mLock) {
            return mActivities.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryActivities(Intent intent, String resolvedType, int flags,
            List<ParsedActivity> activities, int userId) {
        synchronized (mLock) {
            return mActivities.queryIntentForPackage(
                    intent, resolvedType, flags, activities, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryProviders(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mProviders.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryProviders(Intent intent, String resolvedType, int flags,
            List<ParsedProvider> providers, int userId) {
        synchronized (mLock) {
            return mProviders.queryIntentForPackage(intent, resolvedType, flags, providers, userId);
        }
    }

    @Nullable
    List<ProviderInfo> queryProviders(String processName, String metaDataKey, int uid, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) {
            return null;
        }
        List<ProviderInfo> providerList = null;
        CachedApplicationInfoGenerator appInfoGenerator = null;
        synchronized (mLock) {
            for (int i = mProviders.mProviders.size() - 1; i >= 0; --i) {
                final ParsedProvider p = mProviders.mProviders.valueAt(i);
                if (p.getAuthority() == null) {
                    continue;
                }

                final PackageSetting ps =
                        (PackageSetting) sPackageManagerInternal.getPackageSetting(
                                p.getPackageName());
                if (ps == null) {
                    continue;
                }

                AndroidPackage pkg = sPackageManagerInternal.getPackage(p.getPackageName());
                if (pkg == null) {
                    continue;
                }

                if (processName != null && (!p.getProcessName().equals(processName)
                        || !UserHandle.isSameApp(pkg.getUid(), uid))) {
                    continue;
                }
                // See PM.queryContentProviders()'s javadoc for why we have the metaData parameter.
                if (metaDataKey != null
                        && (p.getMetaData() == null || !p.getMetaData().containsKey(metaDataKey))) {
                    continue;
                }
                if (appInfoGenerator == null) {
                    appInfoGenerator = new CachedApplicationInfoGenerator();
                }
                final PackageUserState state = ps.readUserState(userId);
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
        }
        return providerList;
    }

    @Nullable
    ProviderInfo queryProvider(String authority, int flags, int userId) {
        synchronized (mLock) {
            final ParsedProvider p = mProvidersByAuthority.get(authority);
            if (p == null) {
                return null;
            }
            final PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    p.getPackageName());
            if (ps == null) {
                return null;
            }
            final AndroidPackage pkg = sPackageManagerInternal.getPackage(p.getPackageName());
            if (pkg == null) {
                return null;
            }
            final PackageUserState state = ps.readUserState(userId);
            ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(
                    pkg, flags, state, userId, ps);
            if (appInfo == null) {
                return null;
            }
            return PackageInfoUtils.generateProviderInfo(pkg, p, flags, state, appInfo, userId, ps);
        }
    }

    void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo, boolean safeMode,
            int userId) {
        synchronized (mLock) {
            CachedApplicationInfoGenerator appInfoGenerator = null;
            for (int i = mProvidersByAuthority.size() - 1; i >= 0; --i) {
                final ParsedProvider p = mProvidersByAuthority.valueAt(i);
                if (!p.isSyncable()) {
                    continue;
                }

                final PackageSetting ps =
                        (PackageSetting) sPackageManagerInternal.getPackageSetting(
                                p.getPackageName());
                if (ps == null) {
                    continue;
                }

                final AndroidPackage pkg = sPackageManagerInternal.getPackage(p.getPackageName());
                if (pkg == null) {
                    continue;
                }

                if (safeMode && !pkg.isSystem()) {
                    continue;
                }
                if (appInfoGenerator == null) {
                    appInfoGenerator = new CachedApplicationInfoGenerator();
                }
                final PackageUserState state = ps.readUserState(userId);
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
    }

    @Nullable
    List<ResolveInfo> queryReceivers(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mReceivers.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryReceivers(Intent intent, String resolvedType, int flags,
            List<ParsedActivity> receivers, int userId) {
        synchronized (mLock) {
            return mReceivers.queryIntentForPackage(intent, resolvedType, flags, receivers, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryServices(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mServices.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    @Nullable
    List<ResolveInfo> queryServices(Intent intent, String resolvedType, int flags,
            List<ParsedService> services, int userId) {
        synchronized (mLock) {
            return mServices.queryIntentForPackage(intent, resolvedType, flags, services, userId);
        }
    }

    /** Returns {@code true} if the given activity is defined by some package */
    boolean isActivityDefined(ComponentName component) {
        synchronized (mLock) {
            return mActivities.mActivities.get(component) != null;
        }
    }

    /** Asserts none of the providers defined in the given package haven't already been defined. */
    void assertProvidersNotDefined(AndroidPackage pkg) throws PackageManagerException {
        synchronized (mLock) {
            assertProvidersNotDefinedLocked(pkg);
        }
    }

    /** Add all components defined in the given package to the internal structures. */
    void addAllComponents(AndroidPackage pkg, boolean chatty) {
        final ArrayList<Pair<ParsedActivity, ParsedIntentInfo>> newIntents = new ArrayList<>();
        synchronized (mLock) {
            addActivitiesLocked(pkg, newIntents, chatty);
            addReceiversLocked(pkg, chatty);
            addProvidersLocked(pkg, chatty);
            addServicesLocked(pkg, chatty);
            onChanged();
        }
        // expect single setupwizard package
        final String setupWizardPackage = ArrayUtils.firstOrNull(
                sPackageManagerInternal.getKnownPackageNames(
                        PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM));

        for (int i = newIntents.size() - 1; i >= 0; --i) {
            final Pair<ParsedActivity, ParsedIntentInfo> pair = newIntents.get(i);
            final PackageSetting disabledPkgSetting = (PackageSetting) sPackageManagerInternal
                    .getDisabledSystemPackage(pair.first.getPackageName());
            final AndroidPackage disabledPkg =
                    disabledPkgSetting == null ? null : disabledPkgSetting.pkg;
            final List<ParsedActivity> systemActivities =
                    disabledPkg != null ? disabledPkg.getActivities() : null;
            adjustPriority(systemActivities, pair.first, pair.second, setupWizardPackage);
            onChanged();
        }
    }

    /** Removes all components defined in the given package from the internal structures. */
    void removeAllComponents(AndroidPackage pkg, boolean chatty) {
        synchronized (mLock) {
            removeAllComponentsLocked(pkg, chatty);
            onChanged();
        }
    }

    /**
     * Reprocess any protected filters that have been deferred. At this point, we've scanned
     * all of the filters defined on the /system partition and know the special components.
     */
    void fixProtectedFilterPriorities() {
        synchronized (mLock) {
            if (!mDeferProtectedFilters) {
                return;
            }
            mDeferProtectedFilters = false;

            if (mProtectedFilters == null || mProtectedFilters.size() == 0) {
                return;
            }
            final List<Pair<ParsedMainComponent, ParsedIntentInfo>> protectedFilters =
                    mProtectedFilters;
            mProtectedFilters = null;

            // expect single setupwizard package
            final String setupWizardPackage = ArrayUtils.firstOrNull(
                sPackageManagerInternal.getKnownPackageNames(
                    PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM));

            if (DEBUG_FILTERS && setupWizardPackage == null) {
                Slog.i(TAG, "No setup wizard;"
                        + " All protected intents capped to priority 0");
            }
            for (int i = protectedFilters.size() - 1; i >= 0; --i) {
                final Pair<ParsedMainComponent, ParsedIntentInfo> pair = protectedFilters.get(i);
                ParsedMainComponent component = pair.first;
                ParsedIntentInfo filter = pair.second;
                String packageName = component.getPackageName();
                String className = component.getClassName();
                if (packageName.equals(setupWizardPackage)) {
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Found setup wizard;"
                                + " allow priority " + filter.getPriority() + ";"
                                + " package: " + packageName
                                + " activity: " + className
                                + " priority: " + filter.getPriority());
                    }
                    // skip setup wizard; allow it to keep the high priority filter
                    continue;
                }
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Protected action; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + filter.getPriority());
                }
                filter.setPriority(0);
            }
            onChanged();
        }
    }

    void dumpActivityResolvers(PrintWriter pw, DumpState dumpState, String packageName) {
        if (mActivities.dump(pw, dumpState.getTitlePrinted() ? "\nActivity Resolver Table:"
                : "Activity Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    void dumpProviderResolvers(PrintWriter pw, DumpState dumpState, String packageName) {
        if (mProviders.dump(pw, dumpState.getTitlePrinted() ? "\nProvider Resolver Table:"
                : "Provider Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    void dumpReceiverResolvers(PrintWriter pw, DumpState dumpState, String packageName) {
        if (mReceivers.dump(pw, dumpState.getTitlePrinted() ? "\nReceiver Resolver Table:"
                : "Receiver Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    void dumpServiceResolvers(PrintWriter pw, DumpState dumpState, String packageName) {
        if (mServices.dump(pw, dumpState.getTitlePrinted() ? "\nService Resolver Table:"
                : "Service Resolver Table:", "  ", packageName,
                dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
            dumpState.setTitlePrinted(true);
        }
    }

    void dumpContentProviders(PrintWriter pw, DumpState dumpState, String packageName) {
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
            pw.print("  ["); pw.print(entry.getKey()); pw.println("]:");
            pw.print("    "); pw.println(p.toString());

            AndroidPackage pkg = sPackageManagerInternal.getPackage(p.getPackageName());

            if (pkg != null) {
                pw.print("      applicationInfo=");
                pw.println(AndroidPackageUtils.generateAppInfoWithoutState(pkg));
            }
        }
    }

    void dumpServicePermissions(PrintWriter pw, DumpState dumpState) {
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

    @GuardedBy("mLock")
    private void addActivitiesLocked(AndroidPackage pkg,
            List<Pair<ParsedActivity, ParsedIntentInfo>> newIntents, boolean chatty) {
        final int activitiesSize = ArrayUtils.size(pkg.getActivities());
        StringBuilder r = null;
        for (int i = 0; i < activitiesSize; i++) {
            ParsedActivity a = pkg.getActivities().get(i);
            mActivities.addActivity(a, "activity", newIntents);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Activities: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addProvidersLocked(AndroidPackage pkg, boolean chatty) {
        final int providersSize = ArrayUtils.size(pkg.getProviders());
        StringBuilder r = null;
        for (int i = 0; i < providersSize; i++) {
            ParsedProvider p = pkg.getProviders().get(i);
            mProviders.addProvider(p);
            if (p.getAuthority() != null) {
                String[] names = p.getAuthority().split(";");

                // TODO(b/135203078): Remove this mutation
                p.setAuthority(null);
                for (int j = 0; j < names.length; j++) {
                    if (j == 1 && p.isSyncable()) {
                        // We only want the first authority for a provider to possibly be
                        // syncable, so if we already added this provider using a different
                        // authority clear the syncable flag. We copy the provider before
                        // changing it because the mProviders object contains a reference
                        // to a provider that we don't want to change.
                        // Only do this for the second authority since the resulting provider
                        // object can be the same for all future authorities for this provider.
                        p = new ParsedProvider(p);
                        p.setSyncable(false);
                    }
                    if (!mProvidersByAuthority.containsKey(names[j])) {
                        mProvidersByAuthority.put(names[j], p);
                        if (p.getAuthority() == null) {
                            p.setAuthority(names[j]);
                        } else {
                            p.setAuthority(p.getAuthority() + ";" + names[j]);
                        }
                        if (DEBUG_PACKAGE_SCANNING && chatty) {
                            Log.d(TAG, "Registered content provider: " + names[j]
                                    + ", className = " + p.getName()
                                    + ", isSyncable = " + p.isSyncable());
                        }
                    } else {
                        final ParsedProvider other =
                                mProvidersByAuthority.get(names[j]);
                        final ComponentName component =
                                (other != null && other.getComponentName() != null)
                                        ? other.getComponentName() : null;
                        final String packageName =
                                component != null ? component.getPackageName() : "?";
                        Slog.w(TAG, "Skipping provider name " + names[j]
                                + " (in package " + pkg.getPackageName() + ")"
                                + ": name already used by " + packageName);
                    }
                }
            }
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(p.getName());
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Providers: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addReceiversLocked(AndroidPackage pkg, boolean chatty) {
        final int receiversSize = ArrayUtils.size(pkg.getReceivers());
        StringBuilder r = null;
        for (int i = 0; i < receiversSize; i++) {
            ParsedActivity a = pkg.getReceivers().get(i);
            mReceivers.addActivity(a, "receiver", null);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Receivers: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addServicesLocked(AndroidPackage pkg, boolean chatty) {
        final int servicesSize = ArrayUtils.size(pkg.getServices());
        StringBuilder r = null;
        for (int i = 0; i < servicesSize; i++) {
            ParsedService s = pkg.getServices().get(i);
            mServices.addService(s);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.getName());
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Services: " + (r == null ? "<NONE>" : r));
        }
    }

    /**
     * <em>WARNING</em> for performance reasons, the passed in intentList WILL BE
     * MODIFIED. Do not pass in a list that should not be changed.
     */
    private static <T> void getIntentListSubset(List<ParsedIntentInfo> intentList,
            Function<ParsedIntentInfo, Iterator<T>> generator, Iterator<T> searchIterator) {
        // loop through the set of actions; every one must be found in the intent filter
        while (searchIterator.hasNext()) {
            // we must have at least one filter in the list to consider a match
            if (intentList.size() == 0) {
                break;
            }

            final T searchAction = searchIterator.next();

            // loop through the set of intent filters
            final Iterator<ParsedIntentInfo> intentIter = intentList.iterator();
            while (intentIter.hasNext()) {
                final ParsedIntentInfo intentInfo = intentIter.next();
                boolean selectionFound = false;

                // loop through the intent filter's selection criteria; at least one
                // of them must match the searched criteria
                final Iterator<T> intentSelectionIter = generator.apply(intentInfo);
                while (intentSelectionIter != null && intentSelectionIter.hasNext()) {
                    final T intentSelection = intentSelectionIter.next();
                    if (intentSelection != null && intentSelection.equals(searchAction)) {
                        selectionFound = true;
                        break;
                    }
                }

                // the selection criteria wasn't found in this filter's set; this filter
                // is not a potential match
                if (!selectionFound) {
                    intentIter.remove();
                }
            }
        }
    }

    private static boolean isProtectedAction(ParsedIntentInfo filter) {
        final Iterator<String> actionsIter = filter.actionsIterator();
        while (actionsIter != null && actionsIter.hasNext()) {
            final String filterAction = actionsIter.next();
            if (PROTECTED_ACTIONS.contains(filterAction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a privileged activity that matches the specified activity names.
     */
    private static ParsedActivity findMatchingActivity(
            List<ParsedActivity> activityList, ParsedActivity activityInfo) {
        for (ParsedActivity sysActivity : activityList) {
            if (sysActivity.getName().equals(activityInfo.getName())) {
                return sysActivity;
            }
            if (sysActivity.getName().equals(activityInfo.getTargetActivity())) {
                return sysActivity;
            }
            if (sysActivity.getTargetActivity() != null) {
                if (sysActivity.getTargetActivity().equals(activityInfo.getName())) {
                    return sysActivity;
                }
                if (sysActivity.getTargetActivity().equals(activityInfo.getTargetActivity())) {
                    return sysActivity;
                }
            }
        }
        return null;
    }

    /**
     * Adjusts the priority of the given intent filter according to policy.
     * <p>
     * <ul>
     * <li>The priority for non privileged applications is capped to '0'</li>
     * <li>The priority for protected actions on privileged applications is capped to '0'</li>
     * <li>The priority for unbundled updates to privileged applications is capped to the
     *      priority defined on the system partition</li>
     * </ul>
     * <p>
     * <em>NOTE:</em> There is one exception. For security reasons, the setup wizard is
     * allowed to obtain any priority on any action.
     */
    private void adjustPriority(List<ParsedActivity> systemActivities, ParsedActivity activity,
            ParsedIntentInfo intent, String setupWizardPackage) {
        // nothing to do; priority is fine as-is
        if (intent.getPriority() <= 0) {
            return;
        }

        String packageName = activity.getPackageName();
        AndroidPackage pkg = sPackageManagerInternal.getPackage(packageName);

        final boolean privilegedApp = pkg.isPrivileged();
        String className = activity.getClassName();
        if (!privilegedApp) {
            // non-privileged applications can never define a priority >0
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Non-privileged app; cap priority to 0;"
                        + " package: " + packageName
                        + " activity: " + className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(0);
            return;
        }

        if (isProtectedAction(intent)) {
            if (mDeferProtectedFilters) {
                // We can't deal with these just yet. No component should ever obtain a
                // >0 priority for a protected actions, with ONE exception -- the setup
                // wizard. The setup wizard, however, cannot be known until we're able to
                // query it for the category CATEGORY_SETUP_WIZARD. Which we can't do
                // until all intent filters have been processed. Chicken, meet egg.
                // Let the filter temporarily have a high priority and rectify the
                // priorities after all system packages have been scanned.
                if (mProtectedFilters == null) {
                    mProtectedFilters = new ArrayList<>();
                }
                mProtectedFilters.add(Pair.create(activity, intent));
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Protected action; save for later;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
            } else {
                if (DEBUG_FILTERS && setupWizardPackage == null) {
                    Slog.i(TAG, "No setup wizard;"
                            + " All protected intents capped to priority 0");
                }
                if (packageName.equals(setupWizardPackage)) {
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Found setup wizard;"
                                + " allow priority " + intent.getPriority() + ";"
                                + " package: " + packageName
                                + " activity: " + className
                                + " priority: " + intent.getPriority());
                    }
                    // setup wizard gets whatever it wants
                    return;
                }
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Protected action; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
            }
            return;
        }

        if (systemActivities == null) {
            // the system package is not disabled; we're parsing the system partition

            // privileged apps on the system image get whatever priority they request
            return;
        }

        // privileged app unbundled update ... try to find the same activity

        ParsedActivity foundActivity = findMatchingActivity(systemActivities, activity);
        if (foundActivity == null) {
            // this is a new activity; it cannot obtain >0 priority
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "New activity; cap priority to 0;"
                        + " package: " + packageName
                        + " activity: " + className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(0);
            return;
        }

        // found activity, now check for filter equivalence

        // a shallow copy is enough; we modify the list, not its contents
        final List<ParsedIntentInfo> intentListCopy =
                new ArrayList<>(foundActivity.getIntents());

        // find matching action subsets
        final Iterator<String> actionsIterator = intent.actionsIterator();
        if (actionsIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::actionsIterator, actionsIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched action; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // find matching category subsets
        final Iterator<String> categoriesIterator = intent.categoriesIterator();
        if (categoriesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::categoriesIterator,
                    categoriesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched category; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // find matching schemes subsets
        final Iterator<String> schemesIterator = intent.schemesIterator();
        if (schemesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::schemesIterator, schemesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched scheme; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // find matching authorities subsets
        final Iterator<IntentFilter.AuthorityEntry> authoritiesIterator =
                intent.authoritiesIterator();
        if (authoritiesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::authoritiesIterator,
                    authoritiesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched authority; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // we found matching filter(s); app gets the max priority of all intents
        int cappedPriority = 0;
        for (int i = intentListCopy.size() - 1; i >= 0; --i) {
            cappedPriority = Math.max(cappedPriority, intentListCopy.get(i).getPriority());
        }
        if (intent.getPriority() > cappedPriority) {
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Found matching filter(s);"
                        + " cap priority to " + cappedPriority + ";"
                        + " package: " + packageName
                        + " activity: " + className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(cappedPriority);
            return;
        }
        // all this for nothing; the requested priority was <= what was on the system
    }

    @GuardedBy("mLock")
    private void removeAllComponentsLocked(AndroidPackage pkg, boolean chatty) {
        int componentSize;
        StringBuilder r;
        int i;

        componentSize = ArrayUtils.size(pkg.getActivities());
        r = null;
        for (i = 0; i < componentSize; i++) {
            ParsedActivity a = pkg.getActivities().get(i);
            mActivities.removeActivity(a, "activity");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Activities: " + (r == null ? "<NONE>" : r));
        }

        componentSize = ArrayUtils.size(pkg.getProviders());
        r = null;
        for (i = 0; i < componentSize; i++) {
            ParsedProvider p = pkg.getProviders().get(i);
            mProviders.removeProvider(p);
            if (p.getAuthority() == null) {
                // Another content provider with this authority existed when this app was
                // installed, so this authority is null. Ignore it as we don't have to
                // unregister the provider.
                continue;
            }
            String[] names = p.getAuthority().split(";");
            for (int j = 0; j < names.length; j++) {
                if (mProvidersByAuthority.get(names[j]) == p) {
                    mProvidersByAuthority.remove(names[j]);
                    if (DEBUG_REMOVE && chatty) {
                        Log.d(TAG, "Unregistered content provider: " + names[j]
                                + ", className = " + p.getName() + ", isSyncable = "
                                + p.isSyncable());
                    }
                }
            }
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(p.getName());
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Providers: " + (r == null ? "<NONE>" : r));
        }

        componentSize = ArrayUtils.size(pkg.getReceivers());
        r = null;
        for (i = 0; i < componentSize; i++) {
            ParsedActivity a = pkg.getReceivers().get(i);
            mReceivers.removeActivity(a, "receiver");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Receivers: " + (r == null ? "<NONE>" : r));
        }

        componentSize = ArrayUtils.size(pkg.getServices());
        r = null;
        for (i = 0; i < componentSize; i++) {
            ParsedService s = pkg.getServices().get(i);
            mServices.removeService(s);
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.getName());
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Services: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void assertProvidersNotDefinedLocked(AndroidPackage pkg)
            throws PackageManagerException {
        final int providersSize = ArrayUtils.size(pkg.getProviders());
        int i;
        for (i = 0; i < providersSize; i++) {
            ParsedProvider p = pkg.getProviders().get(i);
            if (p.getAuthority() != null) {
                final String[] names = p.getAuthority().split(";");
                for (int j = 0; j < names.length; j++) {
                    if (mProvidersByAuthority.containsKey(names[j])) {
                        final ParsedProvider other = mProvidersByAuthority.get(names[j]);
                        final String otherPackageName =
                                (other != null && other.getComponentName() != null)
                                        ? other.getComponentName().getPackageName() : "?";
                        // if we're installing over the same already-installed package, this is ok
                        if (!otherPackageName.equals(pkg.getPackageName())) {
                            throw new PackageManagerException(
                                    INSTALL_FAILED_CONFLICTING_PROVIDER,
                                    "Can't install because provider name " + names[j]
                                            + " (in package " + pkg.getPackageName()
                                            + ") is already used by " + otherPackageName);
                        }
                    }
                }
            }
        }
    }

    private abstract static class MimeGroupsAwareIntentResolver<F extends Pair<?
            extends ParsedComponent, ParsedIntentInfo>, R>
            extends IntentResolver<F, R> {
        private final ArrayMap<String, F[]> mMimeGroupToFilter = new ArrayMap<>();
        private boolean mIsUpdatingMimeGroup = false;

        // Default constructor
        MimeGroupsAwareIntentResolver() {
        }

        // Copy constructor used in creating snapshots
        MimeGroupsAwareIntentResolver(MimeGroupsAwareIntentResolver<F, R> orig) {
            copyFrom(orig);
            copyInto(mMimeGroupToFilter, orig.mMimeGroupToFilter);
            mIsUpdatingMimeGroup = orig.mIsUpdatingMimeGroup;
        }

        @Override
        public void addFilter(F f) {
            IntentFilter intentFilter = getIntentFilter(f);
            applyMimeGroups(f);
            super.addFilter(f);

            if (!mIsUpdatingMimeGroup) {
                register_intent_filter(f, intentFilter.mimeGroupsIterator(), mMimeGroupToFilter,
                        "      MimeGroup: ");
            }
        }

        @Override
        protected void removeFilterInternal(F f) {
            IntentFilter intentFilter = getIntentFilter(f);
            if (!mIsUpdatingMimeGroup) {
                unregister_intent_filter(f, intentFilter.mimeGroupsIterator(), mMimeGroupToFilter,
                        "      MimeGroup: ");
            }

            super.removeFilterInternal(f);
            intentFilter.clearDynamicDataTypes();
        }

        /**
         * Updates MIME group by applying changes to all IntentFilters
         * that contain the group and repopulating m*ToFilter maps accordingly
         *
         * @param packageName package to which MIME group belongs
         * @param mimeGroup MIME group to update
         * @return true, if any intent filters were changed due to this update
         */
        public boolean updateMimeGroup(String packageName, String mimeGroup) {
            F[] filters = mMimeGroupToFilter.get(mimeGroup);
            int n = filters != null ? filters.length : 0;

            mIsUpdatingMimeGroup = true;
            boolean hasChanges = false;
            F filter;
            for (int i = 0; i < n && (filter = filters[i]) != null; i++) {
                if (isPackageForFilter(packageName, filter)) {
                    hasChanges |= updateFilter(filter);
                }
            }
            mIsUpdatingMimeGroup = false;
            return hasChanges;
        }

        private boolean updateFilter(F f) {
            IntentFilter filter = getIntentFilter(f);
            List<String> oldTypes = filter.dataTypes();
            removeFilter(f);
            addFilter(f);
            List<String> newTypes = filter.dataTypes();
            return !equalLists(oldTypes, newTypes);
        }

        private boolean equalLists(List<String> first, List<String> second) {
            if (first == null) {
                return second == null;
            } else if (second == null) {
                return false;
            }

            if (first.size() != second.size()) {
                return false;
            }

            Collections.sort(first);
            Collections.sort(second);
            return first.equals(second);
        }

        private void applyMimeGroups(F f) {
            IntentFilter filter = getIntentFilter(f);

            for (int i = filter.countMimeGroups() - 1; i >= 0; i--) {
                List<String> mimeTypes = sPackageManagerInternal.getMimeGroup(
                        f.first.getPackageName(), filter.getMimeGroup(i));

                for (int typeIndex = mimeTypes.size() - 1; typeIndex >= 0; typeIndex--) {
                    String mimeType = mimeTypes.get(typeIndex);

                    try {
                        filter.addDynamicDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        if (DEBUG) {
                            Slog.w(TAG, "Malformed mime type: " + mimeType, e);
                        }
                    }
                }
            }
        }
    }

    private static class ActivityIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedActivity, ParsedIntentInfo>, ResolveInfo> {

        // Default constructor
        ActivityIntentResolver() {
        }

        // Copy constructor used in creating snapshots
        ActivityIntentResolver(ActivityIntentResolver orig) {
            super(orig);
            mActivities.putAll(orig.mActivities);
            mFlags = orig.mFlags;
        }

        @Override
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = (defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0);
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                    userId);
        }

        List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, List<ParsedActivity> packageActivities, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            if (packageActivities == null) {
                return Collections.emptyList();
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int activitiesSize = packageActivities.size();
            ArrayList<Pair<ParsedActivity, ParsedIntentInfo>[]> listCut =
                    new ArrayList<>(activitiesSize);

            List<ParsedIntentInfo> intentFilters;
            for (int i = 0; i < activitiesSize; ++i) {
                ParsedActivity activity = packageActivities.get(i);
                intentFilters = activity.getIntents();
                if (!intentFilters.isEmpty()) {
                    Pair<ParsedActivity, ParsedIntentInfo>[] array = newArray(intentFilters.size());
                    for (int arrayIndex = 0; arrayIndex < intentFilters.size(); arrayIndex++) {
                        array[arrayIndex] = Pair.create(activity, intentFilters.get(arrayIndex));
                    }
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        protected void addActivity(ParsedActivity a, String type,
                List<Pair<ParsedActivity, ParsedIntentInfo>> newIntents) {
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + ":");
                Log.v(TAG, "    Class=" + a.getName());
            }
            final int intentsSize = a.getIntents().size();
            for (int j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = a.getIntents().get(j);
                if (newIntents != null && "activity".equals(type)) {
                    newIntents.add(Pair.create(a, intent));
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.getName());
                }
                addFilter(Pair.create(a, intent));
            }
        }

        protected void removeActivity(ParsedActivity a, String type) {
            mActivities.remove(a.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + ":");
                Log.v(TAG, "    Class=" + a.getName());
            }
            final int intentsSize = a.getIntents().size();
            for (int j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = a.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(Pair.create(a, intent));
            }
        }

        @Override
        protected boolean allowFilterResult(Pair<ParsedActivity, ParsedIntentInfo> filter,
                List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; --i) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (Objects.equals(destAi.name, filter.first.getName())
                        && Objects.equals(destAi.packageName, filter.first.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected Pair<ParsedActivity, ParsedIntentInfo>[] newArray(int size) {
            //noinspection unchecked
            return (Pair<ParsedActivity, ParsedIntentInfo>[]) new Pair<?, ?>[size];
        }

        @Override
        protected boolean isFilterStopped(Pair<ParsedActivity, ParsedIntentInfo> filter, int userId) {
            return ComponentResolver.isFilterStopped(filter, userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                Pair<ParsedActivity, ParsedIntentInfo> info) {
            return packageName.equals(info.first.getPackageName());
        }

        private void log(String reason, ParsedIntentInfo info, int match,
                int userId) {
            Slog.w(TAG, reason
                    + "; match: "
                    + DebugUtils.flagsToString(IntentFilter.class, "MATCH_", match)
                    + "; userId: " + userId
                    + "; intent info: " + info);
        }

        @Override
        protected ResolveInfo newResult(Pair<ParsedActivity, ParsedIntentInfo> pair,
                int match, int userId) {
            ParsedActivity activity = pair.first;
            ParsedIntentInfo info = pair.second;

            if (!sUserManager.exists(userId)) {
                if (DEBUG) {
                    log("User doesn't exist", info, match, userId);
                }
                return null;
            }

            AndroidPackage pkg = sPackageManagerInternal.getPackage(activity.getPackageName());
            if (pkg == null) {
                return null;
            }

            if (!sPackageManagerInternal.isEnabledAndMatches(activity, mFlags, userId)) {
                if (DEBUG) {
                    log("!PackageManagerInternal.isEnabledAndMatches; mFlags="
                            + DebugUtils.flagsToString(PackageManager.class, "MATCH_", mFlags),
                            info, match, userId);
                }
                return null;
            }
            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    activity.getPackageName());
            if (ps == null) {
                if (DEBUG) {
                    log("info.activity.owner.mExtras == null", info, match, userId);
                }
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ActivityInfo ai = PackageInfoUtils.generateActivityInfo(pkg, activity, mFlags,
                    userState, userId, ps);
            if (ai == null) {
                if (DEBUG) {
                    log("Failed to create ActivityInfo based on " + activity, info, match,
                            userId);
                }
                return null;
            }
            final boolean matchExplicitlyVisibleOnly =
                    (mFlags & PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY) != 0;
            final boolean matchVisibleToInstantApp =
                    (mFlags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean componentVisible =
                    matchVisibleToInstantApp
                    && info.isVisibleToInstantApp()
                    && (!matchExplicitlyVisibleOnly || info.isExplicitlyVisibleToInstantApp());
            final boolean matchInstantApp = (mFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to ephemeral apps
            if (matchVisibleToInstantApp && !(componentVisible || userState.instantApp)) {
                if (DEBUG) {
                    log("Filter(s) not visible to ephemeral apps"
                            + "; matchVisibleToInstantApp=" + matchVisibleToInstantApp
                            + "; matchInstantApp=" + matchInstantApp
                            + "; info.isVisibleToInstantApp()=" + info.isVisibleToInstantApp()
                            + "; matchExplicitlyVisibleOnly=" + matchExplicitlyVisibleOnly
                            + "; info.isExplicitlyVisibleToInstantApp()="
                                    + info.isExplicitlyVisibleToInstantApp(),
                            info, match, userId);
                }
                return null;
            }
            // throw out instant app filters if we're not explicitly requesting them
            if (!matchInstantApp && userState.instantApp) {
                if (DEBUG) {
                    log("Instant app filter is not explicitly requested", info, match, userId);
                }
                return null;
            }
            // throw out instant app filters if updates are available; will trigger
            // instant app resolution
            if (userState.instantApp && ps.isUpdateAvailable()) {
                if (DEBUG) {
                    log("Instant app update is available", info, match, userId);
                }
                return null;
            }
            final ResolveInfo res = new ResolveInfo(info.hasCategory(Intent.CATEGORY_BROWSABLE));
            res.activityInfo = ai;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = info;
            }
            res.handleAllWebDataURI = info.handleAllWebDataURI();
            res.priority = info.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.isHasDefault();
            res.labelRes = info.getLabelRes();
            res.nonLocalizedLabel = info.getNonLocalizedLabel();
            if (sPackageManagerInternal.userNeedsBadging(userId)) {
                res.noResourceId = true;
            } else {
                res.icon = info.getIcon();
            }
            res.iconResourceId = info.getIcon();
            res.system = res.activityInfo.applicationInfo.isSystemApp();
            res.isInstantAppAvailable = userState.instantApp;
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                Pair<ParsedActivity, ParsedIntentInfo> pair) {
            ParsedActivity activity = pair.first;
            ParsedIntentInfo filter = pair.second;

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(activity)));
            out.print(' ');
            ComponentName.printShortString(out, activity.getPackageName(),
                    activity.getClassName());
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(Pair<ParsedActivity, ParsedIntentInfo> filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            @SuppressWarnings("unchecked") Pair<ParsedActivity, ParsedIntentInfo> pair =
                    (Pair<ParsedActivity, ParsedIntentInfo>) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(pair.first)));
            out.print(' ');
            ComponentName.printShortString(out, pair.first.getPackageName(),
                    pair.first.getClassName());
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        @Override
        protected IntentFilter getIntentFilter(
                @NonNull Pair<ParsedActivity, ParsedIntentInfo> input) {
            return input.second;
        }

        protected List<ParsedActivity> getResolveList(AndroidPackage pkg) {
            return pkg.getActivities();
        }

        // Keys are String (activity class name), values are Activity.  This attribute is
        // protected because it is accessed directly from ComponentResolver.  That works
        // even if the attribute is private, but fails for subclasses of
        // ActivityIntentResolver.
        protected final ArrayMap<ComponentName, ParsedActivity> mActivities =
                new ArrayMap<>();
        private int mFlags;
    }

    // Both receivers and activities share a class, but point to different get methods
    private static final class ReceiverIntentResolver extends ActivityIntentResolver {

        // Default constructor
        ReceiverIntentResolver() {
        }

        // Copy constructor used in creating snapshots
        ReceiverIntentResolver(ReceiverIntentResolver orig) {
            super(orig);
        }

        @Override
        protected List<ParsedActivity> getResolveList(AndroidPackage pkg) {
            return pkg.getReceivers();
        }
    }

    private static final class ProviderIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedProvider, ParsedIntentInfo>, ResolveInfo> {
        // Default constructor
        ProviderIntentResolver() {
        }

        // Copy constructor used in creating snapshots
        ProviderIntentResolver(ProviderIntentResolver orig) {
            super(orig);
            mProviders.putAll(orig.mProviders);
            mFlags = orig.mFlags;
        }

        @Override
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        @Nullable
        List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                    userId);
        }

        @Nullable
        List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, List<ParsedProvider> packageProviders, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            if (packageProviders == null) {
                return Collections.emptyList();
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int providersSize = packageProviders.size();
            ArrayList<Pair<ParsedProvider, ParsedIntentInfo>[]> listCut =
                    new ArrayList<>(providersSize);

            List<ParsedIntentInfo> intentFilters;
            for (int i = 0; i < providersSize; ++i) {
                ParsedProvider provider = packageProviders.get(i);
                intentFilters = provider.getIntents();
                if (!intentFilters.isEmpty()) {
                    Pair<ParsedProvider, ParsedIntentInfo>[] array = newArray(intentFilters.size());
                    for (int arrayIndex = 0; arrayIndex < intentFilters.size(); arrayIndex++) {
                        array[arrayIndex] = Pair.create(provider, intentFilters.get(arrayIndex));
                    }
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        void addProvider(ParsedProvider p) {
            if (mProviders.containsKey(p.getComponentName())) {
                Slog.w(TAG, "Provider " + p.getComponentName() + " already defined; ignoring");
                return;
            }

            mProviders.put(p.getComponentName(), p);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  provider:");
                Log.v(TAG, "    Class=" + p.getName());
            }
            final int intentsSize = p.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = p.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.getName());
                }
                addFilter(Pair.create(p, intent));
            }
        }

        void removeProvider(ParsedProvider p) {
            mProviders.remove(p.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  provider:");
                Log.v(TAG, "    Class=" + p.getName());
            }
            final int intentsSize = p.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = p.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(Pair.create(p, intent));
            }
        }

        @Override
        protected boolean allowFilterResult(Pair<ParsedProvider, ParsedIntentInfo> filter,
                List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (Objects.equals(destPi.name, filter.first.getClassName())
                        && Objects.equals(destPi.packageName, filter.first.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected Pair<ParsedProvider, ParsedIntentInfo>[] newArray(int size) {
            //noinspection unchecked
            return (Pair<ParsedProvider, ParsedIntentInfo>[]) new Pair<?, ?>[size];
        }

        @Override
        protected boolean isFilterStopped(Pair<ParsedProvider, ParsedIntentInfo> filter,
                int userId) {
            return ComponentResolver.isFilterStopped(filter, userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                Pair<ParsedProvider, ParsedIntentInfo> info) {
            return packageName.equals(info.first.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(Pair<ParsedProvider, ParsedIntentInfo> pair,
                int match, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }

            ParsedProvider provider = pair.first;
            ParsedIntentInfo filter = pair.second;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(provider.getPackageName());
            if (pkg == null) {
                return null;
            }

            if (!sPackageManagerInternal.isEnabledAndMatches(provider, mFlags, userId)) {
                return null;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    provider.getPackageName());
            if (ps == null) {
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            final boolean matchVisibleToInstantApp = (mFlags
                    & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (mFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to instant applications
            if (matchVisibleToInstantApp
                    && !(filter.isVisibleToInstantApp() || userState.instantApp)) {
                return null;
            }
            // throw out instant application filters if we're not explicitly requesting them
            if (!isInstantApp && userState.instantApp) {
                return null;
            }
            // throw out instant application filters if updates are available; will trigger
            // instant application resolution
            if (userState.instantApp && ps.isUpdateAvailable()) {
                return null;
            }
            final ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(
                    pkg, mFlags, userState, userId, ps);
            if (appInfo == null) {
                return null;
            }
            ProviderInfo pi = PackageInfoUtils.generateProviderInfo(pkg, provider, mFlags,
                    userState, appInfo, userId, ps);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = filter.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = filter.isHasDefault();
            res.labelRes = filter.getLabelRes();
            res.nonLocalizedLabel = filter.getNonLocalizedLabel();
            res.icon = filter.getIcon();
            res.system = res.providerInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                Pair<ParsedProvider, ParsedIntentInfo> pair) {
            ParsedProvider provider = pair.first;
            ParsedIntentInfo filter = pair.second;

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(provider)));
            out.print(' ');
            ComponentName.printShortString(out, provider.getPackageName(), provider.getClassName());
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(Pair<ParsedProvider, ParsedIntentInfo> filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            @SuppressWarnings("unchecked") final Pair<ParsedProvider, ParsedIntentInfo> pair =
                    (Pair<ParsedProvider, ParsedIntentInfo>) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(pair.first)));
            out.print(' ');
            ComponentName.printShortString(out, pair.first.getPackageName(),
                    pair.first.getClassName());
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }

        @Override
        protected IntentFilter getIntentFilter(
                @NonNull Pair<ParsedProvider, ParsedIntentInfo> input) {
            return input.second;
        }

        private final ArrayMap<ComponentName, ParsedProvider> mProviders = new ArrayMap<>();
        private int mFlags;
    }

    private static final class ServiceIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedService, ParsedIntentInfo>, ResolveInfo> {
        // Default constructor
        ServiceIntentResolver() {
        }

        // Copy constructor used in creating snapshots
        ServiceIntentResolver(ServiceIntentResolver orig) {
            copyFrom(orig);
            mServices.putAll(orig.mServices);
            mFlags = orig.mFlags;
        }

        @Override
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0,
                    userId);
        }

        List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, List<ParsedService> packageServices, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageServices == null) {
                return Collections.emptyList();
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int servicesSize = packageServices.size();
            ArrayList<Pair<ParsedService, ParsedIntentInfo>[]> listCut =
                    new ArrayList<>(servicesSize);

            List<ParsedIntentInfo> intentFilters;
            for (int i = 0; i < servicesSize; ++i) {
                ParsedService service = packageServices.get(i);
                intentFilters = service.getIntents();
                if (intentFilters.size() > 0) {
                    Pair<ParsedService, ParsedIntentInfo>[] array = newArray(intentFilters.size());
                    for (int arrayIndex = 0; arrayIndex < intentFilters.size(); arrayIndex++) {
                        array[arrayIndex] = Pair.create(service, intentFilters.get(arrayIndex));
                    }
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        void addService(ParsedService s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  service:");
                Log.v(TAG, "    Class=" + s.getName());
            }
            final int intentsSize = s.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = s.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.getName());
                }
                addFilter(Pair.create(s, intent));
            }
        }

        void removeService(ParsedService s) {
            mServices.remove(s.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  service:");
                Log.v(TAG, "    Class=" + s.getName());
            }
            final int intentsSize = s.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = s.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(Pair.create(s, intent));
            }
        }

        @Override
        protected boolean allowFilterResult(Pair<ParsedService, ParsedIntentInfo> filter,
                List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; --i) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (Objects.equals(destAi.name, filter.first.getClassName())
                        && Objects.equals(destAi.packageName, filter.first.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected Pair<ParsedService, ParsedIntentInfo>[] newArray(int size) {
            //noinspection unchecked
            return (Pair<ParsedService, ParsedIntentInfo>[]) new Pair<?, ?>[size];
        }

        @Override
        protected boolean isFilterStopped(Pair<ParsedService, ParsedIntentInfo> filter, int userId) {
            return ComponentResolver.isFilterStopped(filter, userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                Pair<ParsedService, ParsedIntentInfo> info) {
            return packageName.equals(info.first.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(Pair<ParsedService, ParsedIntentInfo> pair, int match,
                int userId) {
            if (!sUserManager.exists(userId)) return null;

            ParsedService service = pair.first;
            ParsedIntentInfo filter = pair.second;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(service.getPackageName());
            if (pkg == null) {
                return null;
            }

            if (!sPackageManagerInternal.isEnabledAndMatches(service, mFlags, userId)) {
                return null;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    service.getPackageName());
            if (ps == null) {
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ServiceInfo si = PackageInfoUtils.generateServiceInfo(pkg, service, mFlags,
                    userState, userId, ps);
            if (si == null) {
                return null;
            }
            final boolean matchVisibleToInstantApp =
                    (mFlags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (mFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to ephemeral apps
            if (matchVisibleToInstantApp
                    && !(filter.isVisibleToInstantApp() || userState.instantApp)) {
                return null;
            }
            // throw out ephemeral filters if we're not explicitly requesting them
            if (!isInstantApp && userState.instantApp) {
                return null;
            }
            // throw out instant app filters if updates are available; will trigger
            // instant app resolution
            if (userState.instantApp && ps.isUpdateAvailable()) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = si;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = filter.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = filter.isHasDefault();
            res.labelRes = filter.getLabelRes();
            res.nonLocalizedLabel = filter.getNonLocalizedLabel();
            res.icon = filter.getIcon();
            res.system = res.serviceInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                Pair<ParsedService, ParsedIntentInfo> pair) {
            ParsedService service = pair.first;
            ParsedIntentInfo filter = pair.second;

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(service)));
            out.print(' ');
            ComponentName.printShortString(out, service.getPackageName(), service.getClassName());
            out.print(" filter ");
            out.print(Integer.toHexString(System.identityHashCode(filter)));
            if (service.getPermission() != null) {
                out.print(" permission "); out.println(service.getPermission());
            } else {
                out.println();
            }
        }

        @Override
        protected Object filterToLabel(Pair<ParsedService, ParsedIntentInfo> filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            @SuppressWarnings("unchecked") final Pair<ParsedService, ParsedIntentInfo> pair =
                    (Pair<ParsedService, ParsedIntentInfo>) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(pair.first)));
            out.print(' ');
            ComponentName.printShortString(out, pair.first.getPackageName(),
                    pair.first.getClassName());
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        @Override
        protected IntentFilter getIntentFilter(
                @NonNull Pair<ParsedService, ParsedIntentInfo> input) {
            return input.second;
        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, ParsedService> mServices = new ArrayMap<>();
        private int mFlags;
    }

    static final class InstantAppIntentResolver
            extends IntentResolver<AuxiliaryResolveInfo.AuxiliaryFilter,
            AuxiliaryResolveInfo.AuxiliaryFilter> {
        /**
         * The result that has the highest defined order. Ordering applies on a
         * per-package basis. Mapping is from package name to Pair of order and
         * EphemeralResolveInfo.
         * <p>
         * NOTE: This is implemented as a field variable for convenience and efficiency.
         * By having a field variable, we're able to track filter ordering as soon as
         * a non-zero order is defined. Otherwise, multiple loops across the result set
         * would be needed to apply ordering. If the intent resolver becomes re-entrant,
         * this needs to be contained entirely within {@link #filterResults}.
         */
        final ArrayMap<String, Pair<Integer, InstantAppResolveInfo>> mOrderResult =
                new ArrayMap<>();

        @Override
        protected AuxiliaryResolveInfo.AuxiliaryFilter[] newArray(int size) {
            return new AuxiliaryResolveInfo.AuxiliaryFilter[size];
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                AuxiliaryResolveInfo.AuxiliaryFilter responseObj) {
            return true;
        }

        @Override
        protected AuxiliaryResolveInfo.AuxiliaryFilter newResult(
                AuxiliaryResolveInfo.AuxiliaryFilter responseObj, int match, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            final String packageName = responseObj.resolveInfo.getPackageName();
            final Integer order = responseObj.getOrder();
            final Pair<Integer, InstantAppResolveInfo> lastOrderResult =
                    mOrderResult.get(packageName);
            // ordering is enabled and this item's order isn't high enough
            if (lastOrderResult != null && lastOrderResult.first >= order) {
                return null;
            }
            final InstantAppResolveInfo res = responseObj.resolveInfo;
            if (order > 0) {
                // non-zero order, enable ordering
                mOrderResult.put(packageName, new Pair<>(order, res));
            }
            return responseObj;
        }

        @Override
        protected void filterResults(List<AuxiliaryResolveInfo.AuxiliaryFilter> results) {
            // only do work if ordering is enabled [most of the time it won't be]
            if (mOrderResult.size() == 0) {
                return;
            }
            int resultSize = results.size();
            for (int i = 0; i < resultSize; i++) {
                final InstantAppResolveInfo info = results.get(i).resolveInfo;
                final String packageName = info.getPackageName();
                final Pair<Integer, InstantAppResolveInfo> savedInfo =
                        mOrderResult.get(packageName);
                if (savedInfo == null) {
                    // package doesn't having ordering
                    continue;
                }
                if (savedInfo.second == info) {
                    // circled back to the highest ordered item; remove from order list
                    mOrderResult.remove(packageName);
                    if (mOrderResult.size() == 0) {
                        // no more ordered items
                        break;
                    }
                    continue;
                }
                // item has a worse order, remove it from the result list
                results.remove(i);
                resultSize--;
                i--;
            }
        }

        @Override
        protected IntentFilter getIntentFilter(
                @NonNull AuxiliaryResolveInfo.AuxiliaryFilter input) {
            return input;
        }
    }

    private static boolean isFilterStopped(Pair<? extends ParsedComponent, ParsedIntentInfo> pair,
            int userId) {
        if (!sUserManager.exists(userId)) {
            return true;
        }

        AndroidPackage pkg = sPackageManagerInternal.getPackage(pair.first.getPackageName());
        if (pkg == null) {
            return false;
        }

        PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                pair.first.getPackageName());
        if (ps == null) {
            return false;
        }

        // System apps are never considered stopped for purposes of
        // filtering, because there may be no way for the user to
        // actually re-launch them.
        return !ps.isSystem() && ps.getStopped(userId);
    }

    /** Generic to create an {@link Iterator} for a data type */
    static class IterGenerator<E> {
        public Iterator<E> generate(ParsedIntentInfo info) {
            return null;
        }
    }

    /** Create an {@link Iterator} for intent actions */
    static class ActionIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedIntentInfo info) {
            return info.actionsIterator();
        }
    }

    /** Create an {@link Iterator} for intent categories */
    static class CategoriesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedIntentInfo info) {
            return info.categoriesIterator();
        }
    }

    /** Create an {@link Iterator} for intent schemes */
    static class SchemesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedIntentInfo info) {
            return info.schemesIterator();
        }
    }

    /** Create an {@link Iterator} for intent authorities */
    static class AuthoritiesIterGenerator extends IterGenerator<IntentFilter.AuthorityEntry> {
        @Override
        public Iterator<IntentFilter.AuthorityEntry> generate(ParsedIntentInfo info) {
            return info.authoritiesIterator();
        }
    }

    /**
     * Removes MIME type from the group, by delegating to IntentResolvers
     * @return true if any intent filters were changed due to this update
     */
    boolean updateMimeGroup(String packageName, String group) {
        boolean hasChanges = false;
        synchronized (mLock) {
            hasChanges |= mActivities.updateMimeGroup(packageName, group);
            hasChanges |= mProviders.updateMimeGroup(packageName, group);
            hasChanges |= mReceivers.updateMimeGroup(packageName, group);
            hasChanges |= mServices.updateMimeGroup(packageName, group);
            if (hasChanges) {
                onChanged();
            }
        }
        return hasChanges;
    }
}
