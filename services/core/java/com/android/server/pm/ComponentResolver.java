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
import static com.android.server.pm.PackageManagerService.fixProcessName;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ActivityIntentInfo;
import android.content.pm.PackageParser.ServiceIntentInfo;
import android.content.pm.PackageUserState;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.IntentResolver;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves all Android component types [activities, services, providers and receivers]. */
public class ComponentResolver {
    private static final boolean DEBUG = false;
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG_FILTERS = false;
    private static final boolean DEBUG_SHOW_INFO = false;

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
    private final Object mLock;

    /** All available activities, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ActivityIntentResolver mActivities = new ActivityIntentResolver();

    /** All available providers, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ProviderIntentResolver mProviders = new ProviderIntentResolver();

    /** All available receivers, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ActivityIntentResolver mReceivers = new ActivityIntentResolver();

    /** All available services, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ServiceIntentResolver mServices = new ServiceIntentResolver();

    /** Mapping from provider authority [first directory in content URI codePath) to provider. */
    @GuardedBy("mLock")
    private final ArrayMap<String, PackageParser.Provider> mProvidersByAuthority = new ArrayMap<>();

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
     */
    private List<PackageParser.ActivityIntentInfo> mProtectedFilters;

    ComponentResolver(UserManagerService userManager,
            PackageManagerInternal packageManagerInternal,
            Object lock) {
        sPackageManagerInternal = packageManagerInternal;
        sUserManager = userManager;
        mLock = lock;
    }

    /** Returns the given activity */
    PackageParser.Activity getActivity(ComponentName component) {
        synchronized (mLock) {
            return mActivities.mActivities.get(component);
        }
    }

    /** Returns the given provider */
    PackageParser.Provider getProvider(ComponentName component) {
        synchronized (mLock) {
            return mProviders.mProviders.get(component);
        }
    }

    /** Returns the given receiver */
    PackageParser.Activity getReceiver(ComponentName component) {
        synchronized (mLock) {
            return mReceivers.mActivities.get(component);
        }
    }

    /** Returns the given service */
    PackageParser.Service getService(ComponentName component) {
        synchronized (mLock) {
            return mServices.mServices.get(component);
        }
    }

    List<ResolveInfo> queryActivities(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mActivities.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    List<ResolveInfo> queryActivities(Intent intent, String resolvedType, int flags,
            List<PackageParser.Activity> activities, int userId) {
        synchronized (mLock) {
            return mActivities.queryIntentForPackage(
                    intent, resolvedType, flags, activities, userId);
        }
    }

    List<ResolveInfo> queryProviders(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mProviders.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    List<ResolveInfo> queryProviders(Intent intent, String resolvedType, int flags,
            List<PackageParser.Provider> providers, int userId) {
        synchronized (mLock) {
            return mProviders.queryIntentForPackage(intent, resolvedType, flags, providers, userId);
        }
    }

    List<ProviderInfo> queryProviders(String processName, String metaDataKey, int uid, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) {
            return null;
        }
        List<ProviderInfo> providerList = null;
        synchronized (mLock) {
            for (int i = mProviders.mProviders.size() - 1; i >= 0; --i) {
                final PackageParser.Provider p = mProviders.mProviders.valueAt(i);
                final PackageSetting ps = (PackageSetting) p.owner.mExtras;
                if (ps == null) {
                    continue;
                }
                if (p.info.authority == null) {
                    continue;
                }
                if (processName != null && (!p.info.processName.equals(processName)
                        || !UserHandle.isSameApp(p.info.applicationInfo.uid, uid))) {
                    continue;
                }
                // See PM.queryContentProviders()'s javadoc for why we have the metaData parameter.
                if (metaDataKey != null
                        && (p.metaData == null || !p.metaData.containsKey(metaDataKey))) {
                    continue;
                }
                final ProviderInfo info = PackageParser.generateProviderInfo(
                        p, flags, ps.readUserState(userId), userId);
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

    ProviderInfo queryProvider(String authority, int flags, int userId) {
        synchronized (mLock) {
            final PackageParser.Provider p = mProvidersByAuthority.get(authority);
            if (p == null) {
                return null;
            }
            final PackageSetting ps = (PackageSetting) p.owner.mExtras;
            if (ps == null) {
                return null;
            }
            return PackageParser.generateProviderInfo(p, flags, ps.readUserState(userId), userId);
        }
    }

    void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo, boolean safeMode,
            int userId) {
        synchronized (mLock) {
            for (int i = mProvidersByAuthority.size() - 1; i >= 0; --i) {
                final PackageParser.Provider p = mProvidersByAuthority.valueAt(i);
                final PackageSetting ps = (PackageSetting) p.owner.mExtras;
                if (ps == null) {
                    continue;
                }
                if (!p.syncable) {
                    continue;
                }
                if (safeMode
                        && (p.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue;
                }
                final ProviderInfo info =
                        PackageParser.generateProviderInfo(p, 0, ps.readUserState(userId), userId);
                if (info == null) {
                    continue;
                }
                outNames.add(mProvidersByAuthority.keyAt(i));
                outInfo.add(info);
            }
        }
    }

    List<ResolveInfo> queryReceivers(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mReceivers.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    List<ResolveInfo> queryReceivers(Intent intent, String resolvedType, int flags,
            List<PackageParser.Activity> receivers, int userId) {
        synchronized (mLock) {
            return mReceivers.queryIntentForPackage(intent, resolvedType, flags, receivers, userId);
        }
    }

    List<ResolveInfo> queryServices(Intent intent, String resolvedType, int flags, int userId) {
        synchronized (mLock) {
            return mServices.queryIntent(intent, resolvedType, flags, userId);
        }
    }

    List<ResolveInfo> queryServices(Intent intent, String resolvedType, int flags,
            List<PackageParser.Service> services, int userId) {
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
    void assertProvidersNotDefined(PackageParser.Package pkg) throws PackageManagerException {
        synchronized (mLock) {
            assertProvidersNotDefinedLocked(pkg);
        }
    }

    /** Add all components defined in the given package to the internal structures. */
    void addAllComponents(PackageParser.Package pkg, boolean chatty) {
        final ArrayList<PackageParser.ActivityIntentInfo> newIntents = new ArrayList<>();
        synchronized (mLock) {
            addActivitiesLocked(pkg, newIntents, chatty);
            addReceiversLocked(pkg, chatty);
            addProvidersLocked(pkg, chatty);
            addServicesLocked(pkg, chatty);
        }
        final String setupWizardPackage = sPackageManagerInternal.getKnownPackageName(
                PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM);
        for (int i = newIntents.size() - 1; i >= 0; --i) {
            final PackageParser.ActivityIntentInfo intentInfo = newIntents.get(i);
            final PackageParser.Package disabledPkg = sPackageManagerInternal
                    .getDisabledSystemPackage(intentInfo.activity.info.packageName);
            final List<PackageParser.Activity> systemActivities =
                    disabledPkg != null ? disabledPkg.activities : null;
            adjustPriority(systemActivities, intentInfo, setupWizardPackage);
        }
    }

    /** Removes all components defined in the given package from the internal structures. */
    void removeAllComponents(PackageParser.Package pkg, boolean chatty) {
        synchronized (mLock) {
            removeAllComponentsLocked(pkg, chatty);
        }
    }

    /**
     * Reprocess any protected filters that have been deferred. At this point, we've scanned
     * all of the filters defined on the /system partition and know the special components.
     */
    void fixProtectedFilterPriorities() {
        if (!mDeferProtectedFilters) {
            return;
        }
        mDeferProtectedFilters = false;

        if (mProtectedFilters == null || mProtectedFilters.size() == 0) {
            return;
        }
        final List<ActivityIntentInfo> protectedFilters = mProtectedFilters;
        mProtectedFilters = null;

        final String setupWizardPackage = sPackageManagerInternal.getKnownPackageName(
                PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM);
        if (DEBUG_FILTERS && setupWizardPackage == null) {
            Slog.i(TAG, "No setup wizard;"
                    + " All protected intents capped to priority 0");
        }
        for (int i = protectedFilters.size() - 1; i >= 0; --i) {
            final ActivityIntentInfo filter = protectedFilters.get(i);
            if (filter.activity.info.packageName.equals(setupWizardPackage)) {
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Found setup wizard;"
                            + " allow priority " + filter.getPriority() + ";"
                            + " package: " + filter.activity.info.packageName
                            + " activity: " + filter.activity.className
                            + " priority: " + filter.getPriority());
                }
                // skip setup wizard; allow it to keep the high priority filter
                continue;
            }
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Protected action; cap priority to 0;"
                        + " package: " + filter.activity.info.packageName
                        + " activity: " + filter.activity.className
                        + " origPrio: " + filter.getPriority());
            }
            filter.setPriority(0);
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
        for (PackageParser.Provider p : mProviders.mProviders.values()) {
            if (packageName != null && !packageName.equals(p.info.packageName)) {
                continue;
            }
            if (!printedSomething) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("Registered ContentProviders:");
                printedSomething = true;
            }
            pw.print("  "); p.printComponentShortName(pw); pw.println(":");
            pw.print("    "); pw.println(p.toString());
        }
        printedSomething = false;
        for (Map.Entry<String, PackageParser.Provider> entry :
                mProvidersByAuthority.entrySet()) {
            PackageParser.Provider p = entry.getValue();
            if (packageName != null && !packageName.equals(p.info.packageName)) {
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
            if (p.info != null && p.info.applicationInfo != null) {
                final String appInfo = p.info.applicationInfo.toString();
                pw.print("      applicationInfo="); pw.println(appInfo);
            }
        }
    }

    void dumpServicePermissions(PrintWriter pw, DumpState dumpState, String packageName) {
        if (dumpState.onTitlePrinted()) pw.println();
        pw.println("Service permissions:");

        final Iterator<ServiceIntentInfo> filterIterator = mServices.filterIterator();
        while (filterIterator.hasNext()) {
            final ServiceIntentInfo info = filterIterator.next();
            final ServiceInfo serviceInfo = info.service.info;
            final String permission = serviceInfo.permission;
            if (permission != null) {
                pw.print("    ");
                pw.print(serviceInfo.getComponentName().flattenToShortString());
                pw.print(": ");
                pw.println(permission);
            }
        }
    }

    @GuardedBy("mLock")
    private void addActivitiesLocked(PackageParser.Package pkg,
            List<PackageParser.ActivityIntentInfo> newIntents, boolean chatty) {
        final int activitiesSize = pkg.activities.size();
        StringBuilder r = null;
        for (int i = 0; i < activitiesSize; i++) {
            PackageParser.Activity a = pkg.activities.get(i);
            a.info.processName =
                    fixProcessName(pkg.applicationInfo.processName, a.info.processName);
            mActivities.addActivity(a, "activity", newIntents);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Activities: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addProvidersLocked(PackageParser.Package pkg, boolean chatty) {
        final int providersSize = pkg.providers.size();
        StringBuilder r = null;
        for (int i = 0; i < providersSize; i++) {
            PackageParser.Provider p = pkg.providers.get(i);
            p.info.processName = fixProcessName(pkg.applicationInfo.processName,
                    p.info.processName);
            mProviders.addProvider(p);
            p.syncable = p.info.isSyncable;
            if (p.info.authority != null) {
                String[] names = p.info.authority.split(";");
                p.info.authority = null;
                for (int j = 0; j < names.length; j++) {
                    if (j == 1 && p.syncable) {
                        // We only want the first authority for a provider to possibly be
                        // syncable, so if we already added this provider using a different
                        // authority clear the syncable flag. We copy the provider before
                        // changing it because the mProviders object contains a reference
                        // to a provider that we don't want to change.
                        // Only do this for the second authority since the resulting provider
                        // object can be the same for all future authorities for this provider.
                        p = new PackageParser.Provider(p);
                        p.syncable = false;
                    }
                    if (!mProvidersByAuthority.containsKey(names[j])) {
                        mProvidersByAuthority.put(names[j], p);
                        if (p.info.authority == null) {
                            p.info.authority = names[j];
                        } else {
                            p.info.authority = p.info.authority + ";" + names[j];
                        }
                        if (DEBUG_PACKAGE_SCANNING && chatty) {
                            Log.d(TAG, "Registered content provider: " + names[j]
                                    + ", className = " + p.info.name
                                    + ", isSyncable = " + p.info.isSyncable);
                        }
                    } else {
                        final PackageParser.Provider other =
                                mProvidersByAuthority.get(names[j]);
                        final ComponentName component =
                                (other != null && other.getComponentName() != null)
                                        ? other.getComponentName() : null;
                        final String packageName =
                                component != null ? component.getPackageName() : "?";
                        Slog.w(TAG, "Skipping provider name " + names[j]
                                + " (in package " + pkg.applicationInfo.packageName + ")"
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
                r.append(p.info.name);
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Providers: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addReceiversLocked(PackageParser.Package pkg, boolean chatty) {
        final int receiversSize = pkg.receivers.size();
        StringBuilder r = null;
        for (int i = 0; i < receiversSize; i++) {
            PackageParser.Activity a = pkg.receivers.get(i);
            a.info.processName = fixProcessName(pkg.applicationInfo.processName,
                    a.info.processName);
            mReceivers.addActivity(a, "receiver", null);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (DEBUG_PACKAGE_SCANNING && chatty) {
            Log.d(TAG, "  Receivers: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void addServicesLocked(PackageParser.Package pkg, boolean chatty) {
        final int servicesSize = pkg.services.size();
        StringBuilder r = null;
        for (int i = 0; i < servicesSize; i++) {
            PackageParser.Service s = pkg.services.get(i);
            s.info.processName = fixProcessName(pkg.applicationInfo.processName,
                    s.info.processName);
            mServices.addService(s);
            if (DEBUG_PACKAGE_SCANNING && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.info.name);
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
    private static <T> void getIntentListSubset(List<ActivityIntentInfo> intentList,
            IterGenerator<T> generator, Iterator<T> searchIterator) {
        // loop through the set of actions; every one must be found in the intent filter
        while (searchIterator.hasNext()) {
            // we must have at least one filter in the list to consider a match
            if (intentList.size() == 0) {
                break;
            }

            final T searchAction = searchIterator.next();

            // loop through the set of intent filters
            final Iterator<ActivityIntentInfo> intentIter = intentList.iterator();
            while (intentIter.hasNext()) {
                final ActivityIntentInfo intentInfo = intentIter.next();
                boolean selectionFound = false;

                // loop through the intent filter's selection criteria; at least one
                // of them must match the searched criteria
                final Iterator<T> intentSelectionIter = generator.generate(intentInfo);
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

    private static boolean isProtectedAction(ActivityIntentInfo filter) {
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
    private static PackageParser.Activity findMatchingActivity(
            List<PackageParser.Activity> activityList, ActivityInfo activityInfo) {
        for (PackageParser.Activity sysActivity : activityList) {
            if (sysActivity.info.name.equals(activityInfo.name)) {
                return sysActivity;
            }
            if (sysActivity.info.name.equals(activityInfo.targetActivity)) {
                return sysActivity;
            }
            if (sysActivity.info.targetActivity != null) {
                if (sysActivity.info.targetActivity.equals(activityInfo.name)) {
                    return sysActivity;
                }
                if (sysActivity.info.targetActivity.equals(activityInfo.targetActivity)) {
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
    private void adjustPriority(List<PackageParser.Activity> systemActivities,
            ActivityIntentInfo intent, String setupWizardPackage) {
        // nothing to do; priority is fine as-is
        if (intent.getPriority() <= 0) {
            return;
        }

        final ActivityInfo activityInfo = intent.activity.info;
        final ApplicationInfo applicationInfo = activityInfo.applicationInfo;

        final boolean privilegedApp =
                ((applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0);
        if (!privilegedApp) {
            // non-privileged applications can never define a priority >0
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Non-privileged app; cap priority to 0;"
                        + " package: " + applicationInfo.packageName
                        + " activity: " + intent.activity.className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(0);
            return;
        }

        if (systemActivities == null) {
            // the system package is not disabled; we're parsing the system partition
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
                    mProtectedFilters.add(intent);
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Protected action; save for later;"
                                + " package: " + applicationInfo.packageName
                                + " activity: " + intent.activity.className
                                + " origPrio: " + intent.getPriority());
                    }
                    return;
                } else {
                    if (DEBUG_FILTERS && setupWizardPackage == null) {
                        Slog.i(TAG, "No setup wizard;"
                                + " All protected intents capped to priority 0");
                    }
                    if (intent.activity.info.packageName.equals(setupWizardPackage)) {
                        if (DEBUG_FILTERS) {
                            Slog.i(TAG, "Found setup wizard;"
                                    + " allow priority " + intent.getPriority() + ";"
                                    + " package: " + intent.activity.info.packageName
                                    + " activity: " + intent.activity.className
                                    + " priority: " + intent.getPriority());
                        }
                        // setup wizard gets whatever it wants
                        return;
                    }
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Protected action; cap priority to 0;"
                                + " package: " + intent.activity.info.packageName
                                + " activity: " + intent.activity.className
                                + " origPrio: " + intent.getPriority());
                    }
                    intent.setPriority(0);
                    return;
                }
            }
            // privileged apps on the system image get whatever priority they request
            return;
        }

        // privileged app unbundled update ... try to find the same activity
        final PackageParser.Activity foundActivity =
                findMatchingActivity(systemActivities, activityInfo);
        if (foundActivity == null) {
            // this is a new activity; it cannot obtain >0 priority
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "New activity; cap priority to 0;"
                        + " package: " + applicationInfo.packageName
                        + " activity: " + intent.activity.className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(0);
            return;
        }

        // found activity, now check for filter equivalence

        // a shallow copy is enough; we modify the list, not its contents
        final List<ActivityIntentInfo> intentListCopy = new ArrayList<>(foundActivity.intents);
        final List<ActivityIntentInfo> foundFilters = mActivities.findFilters(intent);

        // find matching action subsets
        final Iterator<String> actionsIterator = intent.actionsIterator();
        if (actionsIterator != null) {
            getIntentListSubset(intentListCopy, new ActionIterGenerator(), actionsIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched action; cap priority to 0;"
                            + " package: " + applicationInfo.packageName
                            + " activity: " + intent.activity.className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // find matching category subsets
        final Iterator<String> categoriesIterator = intent.categoriesIterator();
        if (categoriesIterator != null) {
            getIntentListSubset(intentListCopy, new CategoriesIterGenerator(), categoriesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched category; cap priority to 0;"
                            + " package: " + applicationInfo.packageName
                            + " activity: " + intent.activity.className
                            + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
                return;
            }
        }

        // find matching schemes subsets
        final Iterator<String> schemesIterator = intent.schemesIterator();
        if (schemesIterator != null) {
            getIntentListSubset(intentListCopy, new SchemesIterGenerator(), schemesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched scheme; cap priority to 0;"
                            + " package: " + applicationInfo.packageName
                            + " activity: " + intent.activity.className
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
            getIntentListSubset(intentListCopy, new AuthoritiesIterGenerator(),
                    authoritiesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched authority; cap priority to 0;"
                            + " package: " + applicationInfo.packageName
                            + " activity: " + intent.activity.className
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
                        + " package: " + applicationInfo.packageName
                        + " activity: " + intent.activity.className
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(cappedPriority);
            return;
        }
        // all this for nothing; the requested priority was <= what was on the system
    }

    @GuardedBy("mLock")
    private void removeAllComponentsLocked(PackageParser.Package pkg, boolean chatty) {
        int componentSize;
        StringBuilder r;
        int i;

        componentSize = pkg.activities.size();
        r = null;
        for (i = 0; i < componentSize; i++) {
            PackageParser.Activity a = pkg.activities.get(i);
            mActivities.removeActivity(a, "activity");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Activities: " + (r == null ? "<NONE>" : r));
        }

        componentSize = pkg.providers.size();
        r = null;
        for (i = 0; i < componentSize; i++) {
            PackageParser.Provider p = pkg.providers.get(i);
            mProviders.removeProvider(p);
            if (p.info.authority == null) {
                // Another content provider with this authority existed when this app was
                // installed, so this authority is null. Ignore it as we don't have to
                // unregister the provider.
                continue;
            }
            String[] names = p.info.authority.split(";");
            for (int j = 0; j < names.length; j++) {
                if (mProvidersByAuthority.get(names[j]) == p) {
                    mProvidersByAuthority.remove(names[j]);
                    if (DEBUG_REMOVE && chatty) {
                        Log.d(TAG, "Unregistered content provider: " + names[j]
                                + ", className = " + p.info.name + ", isSyncable = "
                                + p.info.isSyncable);
                    }
                }
            }
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(p.info.name);
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Providers: " + (r == null ? "<NONE>" : r));
        }

        componentSize = pkg.receivers.size();
        r = null;
        for (i = 0; i < componentSize; i++) {
            PackageParser.Activity a = pkg.receivers.get(i);
            mReceivers.removeActivity(a, "receiver");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Receivers: " + (r == null ? "<NONE>" : r));
        }

        componentSize = pkg.services.size();
        r = null;
        for (i = 0; i < componentSize; i++) {
            PackageParser.Service s = pkg.services.get(i);
            mServices.removeService(s);
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.info.name);
            }
        }
        if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "  Services: " + (r == null ? "<NONE>" : r));
        }
    }

    @GuardedBy("mLock")
    private void assertProvidersNotDefinedLocked(PackageParser.Package pkg)
            throws PackageManagerException {
        final int providersSize = pkg.providers.size();
        int i;
        for (i = 0; i < providersSize; i++) {
            PackageParser.Provider p = pkg.providers.get(i);
            if (p.info.authority != null) {
                final String[] names = p.info.authority.split(";");
                for (int j = 0; j < names.length; j++) {
                    if (mProvidersByAuthority.containsKey(names[j])) {
                        final PackageParser.Provider other = mProvidersByAuthority.get(names[j]);
                        final String otherPackageName =
                                (other != null && other.getComponentName() != null)
                                        ? other.getComponentName().getPackageName() : "?";
                        // if we're installing over the same already-installed package, this is ok
                        if (!otherPackageName.equals(pkg.packageName)) {
                            throw new PackageManagerException(
                                    INSTALL_FAILED_CONFLICTING_PROVIDER,
                                    "Can't install because provider name " + names[j]
                                            + " (in package " + pkg.applicationInfo.packageName
                                            + ") is already used by " + otherPackageName);
                        }
                    }
                }
            }
        }
    }

    private static final class ActivityIntentResolver
            extends IntentResolver<PackageParser.ActivityIntentInfo, ResolveInfo> {
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
                int flags, List<PackageParser.Activity> packageActivities, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            if (packageActivities == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int activitiesSize = packageActivities.size();
            ArrayList<PackageParser.ActivityIntentInfo[]> listCut = new ArrayList<>(activitiesSize);

            ArrayList<PackageParser.ActivityIntentInfo> intentFilters;
            for (int i = 0; i < activitiesSize; ++i) {
                intentFilters = packageActivities.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ActivityIntentInfo[] array =
                            new PackageParser.ActivityIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        private void addActivity(PackageParser.Activity a, String type,
                List<PackageParser.ActivityIntentInfo> newIntents) {
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO) {
                final CharSequence label = a.info.nonLocalizedLabel != null
                        ? a.info.nonLocalizedLabel
                        : a.info.name;
                Log.v(TAG, "  " + type + " " + label + ":");
            }
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "    Class=" + a.info.name);
            }
            final int intentsSize = a.intents.size();
            for (int j = 0; j < intentsSize; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (newIntents != null && "activity".equals(type)) {
                    newIntents.add(intent);
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.info.name);
                }
                addFilter(intent);
            }
        }

        private void removeActivity(PackageParser.Activity a, String type) {
            mActivities.remove(a.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel
                                : a.info.name) + ":");
                Log.v(TAG, "    Class=" + a.info.name);
            }
            final int intentsSize = a.intents.size();
            for (int j = 0; j < intentsSize; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ActivityIntentInfo filter, List<ResolveInfo> dest) {
            ActivityInfo filterAi = filter.activity.info;
            for (int i = dest.size() - 1; i >= 0; --i) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (destAi.name == filterAi.name && destAi.packageName == filterAi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ActivityIntentInfo[] newArray(int size) {
            return new ActivityIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ActivityIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.activity.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ActivityIntentInfo info) {
            return packageName.equals(info.activity.owner.packageName);
        }

        private void log(String reason, ActivityIntentInfo info, int match,
                int userId) {
            Slog.w(TAG, reason
                    + "; match: "
                    + DebugUtils.flagsToString(IntentFilter.class, "MATCH_", match)
                    + "; userId: " + userId
                    + "; intent info: " + info);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ActivityIntentInfo info,
                int match, int userId) {
            if (!sUserManager.exists(userId)) {
                if (DEBUG) {
                    log("User doesn't exist", info, match, userId);
                }
                return null;
            }
            if (!sPackageManagerInternal.isEnabledAndMatches(info.activity.info, mFlags, userId)) {
                if (DEBUG) {
                    log("!PackageManagerInternal.isEnabledAndMatches; mFlags="
                            + DebugUtils.flagsToString(PackageManager.class, "MATCH_", mFlags),
                            info, match, userId);
                }
                return null;
            }
            final PackageParser.Activity activity = info.activity;
            PackageSetting ps = (PackageSetting) activity.owner.mExtras;
            if (ps == null) {
                if (DEBUG) {
                    log("info.activity.owner.mExtras == null", info, match, userId);
                }
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ActivityInfo ai =
                    PackageParser.generateActivityInfo(activity, mFlags, userState, userId);
            if (ai == null) {
                if (DEBUG) {
                    log("Failed to create ActivityInfo based on " + info.activity, info, match,
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
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = ai;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = info;
            }
            res.handleAllWebDataURI = info.handleAllWebDataURI();
            res.priority = info.getPriority();
            res.preferredOrder = activity.owner.mPreferredOrder;
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            if (sPackageManagerInternal.userNeedsBadging(userId)) {
                res.noResourceId = true;
            } else {
                res.icon = info.icon;
            }
            res.iconResourceId = info.icon;
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
                PackageParser.ActivityIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.activity)));
            out.print(' ');
            filter.activity.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(PackageParser.ActivityIntentInfo filter) {
            return filter.activity;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Activity activity = (PackageParser.Activity) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(activity)));
            out.print(' ');
            activity.printComponentShortName(out);
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, PackageParser.Activity> mActivities =
                new ArrayMap<>();
        private int mFlags;
    }

    private static final class ProviderIntentResolver
            extends IntentResolver<PackageParser.ProviderIntentInfo, ResolveInfo> {
        @Override
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
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
                int flags, List<PackageParser.Provider> packageProviders, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            if (packageProviders == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int providersSize = packageProviders.size();
            ArrayList<PackageParser.ProviderIntentInfo[]> listCut = new ArrayList<>(providersSize);

            ArrayList<PackageParser.ProviderIntentInfo> intentFilters;
            for (int i = 0; i < providersSize; ++i) {
                intentFilters = packageProviders.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ProviderIntentInfo[] array =
                            new PackageParser.ProviderIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        void addProvider(PackageParser.Provider p) {
            if (mProviders.containsKey(p.getComponentName())) {
                Slog.w(TAG, "Provider " + p.getComponentName() + " already defined; ignoring");
                return;
            }

            mProviders.put(p.getComponentName(), p);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (p.info.nonLocalizedLabel != null
                                ? p.info.nonLocalizedLabel
                                : p.info.name)
                        + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int intentsSize = p.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.info.name);
                }
                addFilter(intent);
            }
        }

        void removeProvider(PackageParser.Provider p) {
            mProviders.remove(p.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (p.info.nonLocalizedLabel != null
                        ? p.info.nonLocalizedLabel
                        : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int intentsSize = p.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ProviderIntentInfo filter, List<ResolveInfo> dest) {
            ProviderInfo filterPi = filter.provider.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (destPi.name == filterPi.name
                        && destPi.packageName == filterPi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ProviderIntentInfo[] newArray(int size) {
            return new PackageParser.ProviderIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ProviderIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) {
                return true;
            }
            PackageParser.Package p = filter.provider.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ProviderIntentInfo info) {
            return packageName.equals(info.provider.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ProviderIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }
            final PackageParser.ProviderIntentInfo info = filter;
            if (!sPackageManagerInternal.isEnabledAndMatches(info.provider.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Provider provider = info.provider;
            PackageSetting ps = (PackageSetting) provider.owner.mExtras;
            if (ps == null) {
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            final boolean matchVisibleToInstantApp = (mFlags
                    & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (mFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to instant applications
            if (matchVisibleToInstantApp
                    && !(info.isVisibleToInstantApp() || userState.instantApp)) {
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
            ProviderInfo pi = PackageParser.generateProviderInfo(provider, mFlags,
                    userState, userId);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = info.getPriority();
            res.preferredOrder = provider.owner.mPreferredOrder;
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = res.providerInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ProviderIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.provider)));
            out.print(' ');
            filter.provider.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(PackageParser.ProviderIntentInfo filter) {
            return filter.provider;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            final PackageParser.Provider provider = (PackageParser.Provider) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(provider)));
            out.print(' ');
            provider.printComponentShortName(out);
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }

        private final ArrayMap<ComponentName, PackageParser.Provider> mProviders = new ArrayMap<>();
        private int mFlags;
    }

    private static final class ServiceIntentResolver
            extends IntentResolver<PackageParser.ServiceIntentInfo, ResolveInfo> {
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
                int flags, List<PackageParser.Service> packageServices, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageServices == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int servicesSize = packageServices.size();
            ArrayList<PackageParser.ServiceIntentInfo[]> listCut = new ArrayList<>(servicesSize);

            ArrayList<PackageParser.ServiceIntentInfo> intentFilters;
            for (int i = 0; i < servicesSize; ++i) {
                intentFilters = packageServices.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ServiceIntentInfo[] array =
                            new PackageParser.ServiceIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        void addService(PackageParser.Service s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int intentsSize = s.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.info.name);
                }
                addFilter(intent);
            }
        }

        void removeService(PackageParser.Service s) {
            mServices.remove(s.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int intentsSize = s.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ServiceIntentInfo filter, List<ResolveInfo> dest) {
            ServiceInfo filterSi = filter.service.info;
            for (int i = dest.size() - 1; i >= 0; --i) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (destAi.name == filterSi.name
                        && destAi.packageName == filterSi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ServiceIntentInfo[] newArray(int size) {
            return new PackageParser.ServiceIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ServiceIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.service.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ServiceIntentInfo info) {
            return packageName.equals(info.service.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ServiceIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;
            final PackageParser.ServiceIntentInfo info = (PackageParser.ServiceIntentInfo) filter;
            if (!sPackageManagerInternal.isEnabledAndMatches(info.service.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Service service = info.service;
            PackageSetting ps = (PackageSetting) service.owner.mExtras;
            if (ps == null) {
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ServiceInfo si = PackageParser.generateServiceInfo(service, mFlags,
                    userState, userId);
            if (si == null) {
                return null;
            }
            final boolean matchVisibleToInstantApp =
                    (mFlags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (mFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to ephemeral apps
            if (matchVisibleToInstantApp
                    && !(info.isVisibleToInstantApp() || userState.instantApp)) {
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
            res.priority = info.getPriority();
            res.preferredOrder = service.owner.mPreferredOrder;
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = res.serviceInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ServiceIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.service)));
            out.print(' ');
            filter.service.printComponentShortName(out);
            out.print(" filter ");
            out.print(Integer.toHexString(System.identityHashCode(filter)));
            if (filter.service.info.permission != null) {
                out.print(" permission "); out.println(filter.service.info.permission);
            } else {
                out.println();
            }
        }

        @Override
        protected Object filterToLabel(PackageParser.ServiceIntentInfo filter) {
            return filter.service;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            final PackageParser.Service service = (PackageParser.Service) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(service)));
            out.print(' ');
            service.printComponentShortName(out);
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, PackageParser.Service> mServices = new ArrayMap<>();
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
    }

    /** Generic to create an {@link Iterator} for a data type */
    static class IterGenerator<E> {
        public Iterator<E> generate(ActivityIntentInfo info) {
            return null;
        }
    }

    /** Create an {@link Iterator} for intent actions */
    static class ActionIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ActivityIntentInfo info) {
            return info.actionsIterator();
        }
    }

    /** Create an {@link Iterator} for intent categories */
    static class CategoriesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ActivityIntentInfo info) {
            return info.categoriesIterator();
        }
    }

    /** Create an {@link Iterator} for intent schemes */
    static class SchemesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ActivityIntentInfo info) {
            return info.schemesIterator();
        }
    }

    /** Create an {@link Iterator} for intent authorities */
    static class AuthoritiesIterGenerator extends IterGenerator<IntentFilter.AuthorityEntry> {
        @Override
        public Iterator<IntentFilter.AuthorityEntry> generate(ActivityIntentInfo info) {
            return info.authoritiesIterator();
        }
    }

}
