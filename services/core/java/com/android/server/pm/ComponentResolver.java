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
import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivityIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedProviderIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.content.pm.parsing.ComponentParseUtils.ParsedServiceIntentInfo;
import android.content.pm.parsing.PackageInfoUtils;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.IntentResolver;

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
    private final ActivityIntentResolver mReceivers = new ReceiverIntentResolver();

    /** All available services, for your resolving pleasure. */
    @GuardedBy("mLock")
    private final ServiceIntentResolver mServices = new ServiceIntentResolver();

    /** Mapping from provider authority [first directory in content URI codePath) to provider. */
    @GuardedBy("mLock")
    private final ArrayMap<String, ParsedProvider> mProvidersByAuthority = new ArrayMap<>();

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
    private List<ParsedActivityIntentInfo> mProtectedFilters;

    ComponentResolver(UserManagerService userManager,
            PackageManagerInternal packageManagerInternal,
            Object lock) {
        sPackageManagerInternal = packageManagerInternal;
        sUserManager = userManager;
        mLock = lock;
    }

    /** Returns the given activity */
    ParsedActivity getActivity(ComponentName component) {
        synchronized (mLock) {
            return mActivities.mActivities.get(component);
        }
    }

    /** Returns the given provider */
    ParsedProvider getProvider(ComponentName component) {
        synchronized (mLock) {
            return mProviders.mProviders.get(component);
        }
    }

    /** Returns the given receiver */
    ParsedActivity getReceiver(ComponentName component) {
        synchronized (mLock) {
            return mReceivers.mActivities.get(component);
        }
    }

    /** Returns the given service */
    ParsedService getService(ComponentName component) {
        synchronized (mLock) {
            return mServices.mServices.get(component);
        }
    }

    @Nullable
    List<ResolveInfo> queryActivities(Intent intent, String resolvedType, int flags, int userId) {
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
                final ProviderInfo info = PackageInfoUtils.generateProviderInfo(
                        pkg, p, flags, ps.readUserState(userId), userId);
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
            return PackageInfoUtils.generateProviderInfo(pkg, p, flags,
                    ps.readUserState(userId), userId);
        }
    }

    void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo, boolean safeMode,
            int userId) {
        synchronized (mLock) {
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

                if (safeMode && (pkg.getFlags() & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue;
                }
                final ProviderInfo info =
                        PackageInfoUtils.generateProviderInfo(pkg, p, 0,
                                ps.readUserState(userId), userId);
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
        final ArrayList<ParsedActivityIntentInfo> newIntents = new ArrayList<>();
        synchronized (mLock) {
            addActivitiesLocked(pkg, newIntents, chatty);
            addReceiversLocked(pkg, chatty);
            addProvidersLocked(pkg, chatty);
            addServicesLocked(pkg, chatty);
        }
        // expect single setupwizard package
        final String setupWizardPackage = ArrayUtils.firstOrNull(
                sPackageManagerInternal.getKnownPackageNames(
                        PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM));

        for (int i = newIntents.size() - 1; i >= 0; --i) {
            final ParsedActivityIntentInfo intentInfo = newIntents.get(i);
            final PackageSetting disabledPkgSetting = (PackageSetting) sPackageManagerInternal
                    .getDisabledSystemPackage(intentInfo.getPackageName());
            final AndroidPackage disabledPkg =
                    disabledPkgSetting == null ? null : disabledPkgSetting.pkg;
            final List<ParsedActivity> systemActivities =
                    disabledPkg != null ? disabledPkg.getActivities() : null;
            adjustPriority(systemActivities, intentInfo, setupWizardPackage);
        }
    }

    /** Removes all components defined in the given package from the internal structures. */
    void removeAllComponents(AndroidPackage pkg, boolean chatty) {
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
        final List<ParsedActivityIntentInfo> protectedFilters = mProtectedFilters;
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
            final ParsedActivityIntentInfo filter = protectedFilters.get(i);
            if (filter.getPackageName().equals(setupWizardPackage)) {
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Found setup wizard;"
                            + " allow priority " + filter.getPriority() + ";"
                            + " package: " + filter.getPackageName()
                            + " activity: " + filter.getClassName()
                            + " priority: " + filter.getPriority());
                }
                // skip setup wizard; allow it to keep the high priority filter
                continue;
            }
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Protected action; cap priority to 0;"
                        + " package: " + filter.getPackageName()
                        + " activity: " + filter.getClassName()
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
            ComponentName.printShortString(pw, p.getPackageName(), p.className);
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
                // TODO(b/135203078): Print AppInfo?
                pw.print("      applicationInfo="); pw.println(pkg.toAppInfoWithoutState());
            }
        }
    }

    void dumpServicePermissions(PrintWriter pw, DumpState dumpState) {
        if (dumpState.onTitlePrinted()) pw.println();
        pw.println("Service permissions:");

        final Iterator<ParsedServiceIntentInfo> filterIterator = mServices.filterIterator();
        while (filterIterator.hasNext()) {
            final ParsedServiceIntentInfo info = filterIterator.next();

            ParsedService service = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(info.getPackageName());
            if (pkg != null && pkg.getServices() != null) {
                for (ParsedService parsedService : pkg.getServices()) {
                    if (Objects.equals(parsedService.className, info.getClassName())) {
                        service = parsedService;
                    }
                }
            }

            if (service == null) {
                continue;
            }

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
            List<ParsedActivityIntentInfo> newIntents, boolean chatty) {
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
                                + " (in package " + pkg.getAppInfoPackageName() + ")"
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
    private static <T> void getIntentListSubset(List<ParsedActivityIntentInfo> intentList,
            Function<ParsedActivityIntentInfo, Iterator<T>> generator, Iterator<T> searchIterator) {
        // loop through the set of actions; every one must be found in the intent filter
        while (searchIterator.hasNext()) {
            // we must have at least one filter in the list to consider a match
            if (intentList.size() == 0) {
                break;
            }

            final T searchAction = searchIterator.next();

            // loop through the set of intent filters
            final Iterator<ParsedActivityIntentInfo> intentIter = intentList.iterator();
            while (intentIter.hasNext()) {
                final ParsedActivityIntentInfo intentInfo = intentIter.next();
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

    private static boolean isProtectedAction(ParsedActivityIntentInfo filter) {
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
            if (sysActivity.getName().equals(activityInfo.targetActivity)) {
                return sysActivity;
            }
            if (sysActivity.targetActivity != null) {
                if (sysActivity.targetActivity.equals(activityInfo.getName())) {
                    return sysActivity;
                }
                if (sysActivity.targetActivity.equals(activityInfo.targetActivity)) {
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
    private void adjustPriority(List<ParsedActivity> systemActivities,
            ParsedActivityIntentInfo intent, String setupWizardPackage) {
        // nothing to do; priority is fine as-is
        if (intent.getPriority() <= 0) {
            return;
        }

        AndroidPackage pkg = sPackageManagerInternal.getPackage(intent.getPackageName());

        final boolean privilegedApp =
                ((pkg.getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0);
        if (!privilegedApp) {
            // non-privileged applications can never define a priority >0
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Non-privileged app; cap priority to 0;"
                        + " package: " + pkg.getPackageName()
                        + " activity: " + intent.getClassName()
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
                                + " package: " + pkg.getPackageName()
                                + " activity: " + intent.getClassName()
                                + " origPrio: " + intent.getPriority());
                    }
                    return;
                } else {
                    if (DEBUG_FILTERS && setupWizardPackage == null) {
                        Slog.i(TAG, "No setup wizard;"
                                + " All protected intents capped to priority 0");
                    }
                    if (intent.getPackageName().equals(setupWizardPackage)) {
                        if (DEBUG_FILTERS) {
                            Slog.i(TAG, "Found setup wizard;"
                                    + " allow priority " + intent.getPriority() + ";"
                                    + " package: " + intent.getPackageName()
                                    + " activity: " + intent.getClassName()
                                    + " priority: " + intent.getPriority());
                        }
                        // setup wizard gets whatever it wants
                        return;
                    }
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Protected action; cap priority to 0;"
                                + " package: " + intent.getPackageName()
                                + " activity: " + intent.getClassName()
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

        ParsedActivity foundActivity = null;
        ParsedActivity activity = null;

        if (pkg.getActivities() != null) {
            for (ParsedActivity parsedProvider : pkg.getActivities()) {
                if (Objects.equals(parsedProvider.className, intent.getClassName())) {
                    activity = parsedProvider;
                }
            }
        }

        if (activity != null) {
            foundActivity = findMatchingActivity(systemActivities, activity);
        }

        if (foundActivity == null) {
            // this is a new activity; it cannot obtain >0 priority
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "New activity; cap priority to 0;"
                        + " package: " + pkg.getPackageName()
                        + " activity: " + intent.getClassName()
                        + " origPrio: " + intent.getPriority());
            }
            intent.setPriority(0);
            return;
        }

        // found activity, now check for filter equivalence

        // a shallow copy is enough; we modify the list, not its contents
        final List<ParsedActivityIntentInfo> intentListCopy =
                new ArrayList<>(foundActivity.intents);

        // find matching action subsets
        final Iterator<String> actionsIterator = intent.actionsIterator();
        if (actionsIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::actionsIterator, actionsIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched action; cap priority to 0;"
                            + " package: " + pkg.getPackageName()
                            + " activity: " + intent.getClassName()
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
                            + " package: " + pkg.getPackageName()
                            + " activity: " + intent.getClassName()
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
                            + " package: " + pkg.getPackageName()
                            + " activity: " + intent.getClassName()
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
                            + " package: " + pkg.getPackageName()
                            + " activity: " + intent.getClassName()
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
                        + " package: " + pkg.getPackageName()
                        + " activity: " + intent.getClassName()
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

    private static class ActivityIntentResolver
            extends IntentResolver<ParsedActivityIntentInfo, ResolveInfo> {

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
            ArrayList<ParsedActivityIntentInfo[]> listCut = new ArrayList<>(activitiesSize);

            List<ParsedActivityIntentInfo> intentFilters;
            for (int i = 0; i < activitiesSize; ++i) {
                intentFilters = packageActivities.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    ParsedActivityIntentInfo[] array =
                            new ParsedActivityIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        private void addActivity(ParsedActivity a, String type,
                List<ParsedActivityIntentInfo> newIntents) {
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO) {
                final CharSequence label = a.nonLocalizedLabel != null
                        ? a.nonLocalizedLabel
                        : a.getName();
                Log.v(TAG, "  " + type + " " + label + ":");
            }
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "    Class=" + a.getName());
            }
            final int intentsSize = a.intents.size();
            for (int j = 0; j < intentsSize; j++) {
                ParsedActivityIntentInfo intent = a.intents.get(j);
                if (newIntents != null && "activity".equals(type)) {
                    newIntents.add(intent);
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.getName());
                }
                addFilter(intent);
            }
        }

        private void removeActivity(ParsedActivity a, String type) {
            mActivities.remove(a.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (a.nonLocalizedLabel != null ? a.nonLocalizedLabel
                                : a.getName()) + ":");
                Log.v(TAG, "    Class=" + a.getName());
            }
            final int intentsSize = a.intents.size();
            for (int j = 0; j < intentsSize; j++) {
                ParsedActivityIntentInfo intent = a.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                ParsedActivityIntentInfo filter, List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; --i) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (Objects.equals(destAi.name, filter.getClassName())
                        && Objects.equals(destAi.packageName, filter.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ParsedActivityIntentInfo[] newArray(int size) {
            return new ParsedActivityIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(ParsedActivityIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg == null) {
                return false;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    filter.getPackageName());
            if (ps == null) {
                return false;
            }

            // System apps are never considered stopped for purposes of
            // filtering, because there may be no way for the user to
            // actually re-launch them.
            return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && ps.getStopped(userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                ParsedActivityIntentInfo info) {
            return packageName.equals(info.getPackageName());
        }

        private void log(String reason, ParsedActivityIntentInfo info, int match,
                int userId) {
            Slog.w(TAG, reason
                    + "; match: "
                    + DebugUtils.flagsToString(IntentFilter.class, "MATCH_", match)
                    + "; userId: " + userId
                    + "; intent info: " + info);
        }

        @Override
        protected ResolveInfo newResult(ParsedActivityIntentInfo info,
                int match, int userId) {
            if (!sUserManager.exists(userId)) {
                if (DEBUG) {
                    log("User doesn't exist", info, match, userId);
                }
                return null;
            }

            ParsedActivity activity = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(info.getPackageName());
            if (pkg == null) {
                return null;
            }

            // TODO(b/135203078): Consider more efficient ways of doing this.
            List<ParsedActivity> activities = getResolveList(pkg);
            if (activities != null) {
                for (ParsedActivity parsedActivity : activities) {
                    if (Objects.equals(parsedActivity.className, info.getClassName())) {
                        activity = parsedActivity;
                    }
                }
            }

            if (activity == null) {
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
                    info.getPackageName());
            if (ps == null) {
                if (DEBUG) {
                    log("info.activity.owner.mExtras == null", info, match, userId);
                }
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ActivityInfo ai =
                    PackageInfoUtils.generateActivityInfo(pkg, activity, mFlags, userState, userId);
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
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = ai;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = info;
            }
            res.handleAllWebDataURI = info.handleAllWebDataURI();
            res.priority = info.getPriority();
            res.preferredOrder = pkg.getPreferredOrder();
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
                ParsedActivityIntentInfo filter) {
            ParsedActivity activity = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg != null && pkg.getActivities() != null) {
                for (ParsedActivity parsedActivity : pkg.getActivities()) {
                    if (Objects.equals(parsedActivity.className, filter.getClassName())) {
                        activity = parsedActivity;
                    }
                }
            }

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(activity)));
            out.print(' ');
            ComponentName.printShortString(out, filter.getPackageName(), filter.getClassName());
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(ParsedActivityIntentInfo filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            ParsedActivityIntentInfo activity = (ParsedActivityIntentInfo) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(activity)));
            out.print(' ');
            ComponentName.printShortString(out, activity.getPackageName(), activity.getClassName());
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        protected List<ParsedActivity> getResolveList(AndroidPackage pkg) {
            return pkg.getActivities();
        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, ParsedActivity> mActivities =
                new ArrayMap<>();
        private int mFlags;
    }

    // Both receivers and activities share a class, but point to different get methods
    private static final class ReceiverIntentResolver extends ActivityIntentResolver {

        @Override
        protected List<ParsedActivity> getResolveList(AndroidPackage pkg) {
            return pkg.getReceivers();
        }
    }

    private static final class ProviderIntentResolver
            extends IntentResolver<ParsedProviderIntentInfo, ResolveInfo> {
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
            ArrayList<ParsedProviderIntentInfo[]> listCut = new ArrayList<>(providersSize);

            List<ParsedProviderIntentInfo> intentFilters;
            for (int i = 0; i < providersSize; ++i) {
                intentFilters = packageProviders.get(i).getIntents();
                if (intentFilters != null && intentFilters.size() > 0) {
                    ParsedProviderIntentInfo[] array =
                            new ParsedProviderIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
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
                Log.v(TAG, "  "
                        + (p.nonLocalizedLabel != null
                                ? p.nonLocalizedLabel
                                : p.getName())
                        + ":");
                Log.v(TAG, "    Class=" + p.getName());
            }
            final int intentsSize = p.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedProviderIntentInfo intent = p.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.getName());
                }
                addFilter(intent);
            }
        }

        void removeProvider(ParsedProvider p) {
            mProviders.remove(p.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (p.nonLocalizedLabel != null
                        ? p.nonLocalizedLabel
                        : p.getName()) + ":");
                Log.v(TAG, "    Class=" + p.getName());
            }
            final int intentsSize = p.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedProviderIntentInfo intent = p.getIntents().get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                ParsedProviderIntentInfo filter, List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (Objects.equals(destPi.name, filter.getClassName())
                        && Objects.equals(destPi.packageName, filter.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ParsedProviderIntentInfo[] newArray(int size) {
            return new ParsedProviderIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(ParsedProviderIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) {
                return true;
            }

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg == null) {
                return false;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    filter.getPackageName());
            if (ps == null) {
                return false;
            }

            // System apps are never considered stopped for purposes of
            // filtering, because there may be no way for the user to
            // actually re-launch them.
            return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && ps.getStopped(userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                ParsedProviderIntentInfo info) {
            return packageName.equals(info.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(ParsedProviderIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) {
                return null;
            }

            ParsedProvider provider = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg != null && pkg.getProviders() != null) {
                for (ParsedProvider parsedProvider : pkg.getProviders()) {
                    if (Objects.equals(parsedProvider.className, filter.getClassName())) {
                        provider = parsedProvider;
                    }
                }
            }

            if (provider == null) {
                return null;
            }

            if (!sPackageManagerInternal.isEnabledAndMatches(provider, mFlags, userId)) {
                return null;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    filter.getPackageName());
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
            ProviderInfo pi = PackageInfoUtils.generateProviderInfo(pkg, provider,
                    mFlags, userState, userId);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = filter.getPriority();
            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = filter.hasDefault;
            res.labelRes = filter.labelRes;
            res.nonLocalizedLabel = filter.nonLocalizedLabel;
            res.icon = filter.icon;
            res.system = res.providerInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                ParsedProviderIntentInfo filter) {
            ParsedProvider provider = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg != null && pkg.getProviders() != null) {
                for (ParsedProvider parsedProvider : pkg.getProviders()) {
                    if (Objects.equals(parsedProvider.className, filter.getClassName())) {
                        provider = parsedProvider;
                    }
                }
            }

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(provider)));
            out.print(' ');
            ComponentName.printShortString(out, filter.getPackageName(), filter.getClassName());
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(ParsedProviderIntentInfo filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            final ParsedProviderIntentInfo provider = (ParsedProviderIntentInfo) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(provider)));
            out.print(' ');
            ComponentName.printShortString(out, provider.getPackageName(), provider.getClassName());
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }

        private final ArrayMap<ComponentName, ParsedProvider> mProviders = new ArrayMap<>();
        private int mFlags;
    }

    private static final class ServiceIntentResolver
            extends IntentResolver<ParsedServiceIntentInfo, ResolveInfo> {
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
            ArrayList<ParsedServiceIntentInfo[]> listCut = new ArrayList<>(servicesSize);

            List<ParsedServiceIntentInfo> intentFilters;
            for (int i = 0; i < servicesSize; ++i) {
                intentFilters = packageServices.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    ParsedServiceIntentInfo[] array =
                            new ParsedServiceIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        void addService(ParsedService s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (s.nonLocalizedLabel != null
                        ? s.nonLocalizedLabel : s.getName()) + ":");
                Log.v(TAG, "    Class=" + s.getName());
            }
            final int intentsSize = s.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.getName());
                }
                addFilter(intent);
            }
        }

        void removeService(ParsedService s) {
            mServices.remove(s.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (s.nonLocalizedLabel != null
                        ? s.nonLocalizedLabel : s.getName()) + ":");
                Log.v(TAG, "    Class=" + s.getName());
            }
            final int intentsSize = s.intents.size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                ParsedServiceIntentInfo filter, List<ResolveInfo> dest) {
            for (int i = dest.size() - 1; i >= 0; --i) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (Objects.equals(destAi.name, filter.getClassName())
                        && Objects.equals(destAi.packageName, filter.getPackageName())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ParsedServiceIntentInfo[] newArray(int size) {
            return new ParsedServiceIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(ParsedServiceIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg == null) {
                return false;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    filter.getPackageName());
            if (ps == null) {
                return false;
            }

            // System apps are never considered stopped for purposes of
            // filtering, because there may be no way for the user to
            // actually re-launch them.
            return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && ps.getStopped(userId);
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                ParsedServiceIntentInfo info) {
            return packageName.equals(info.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(ParsedServiceIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;

            ParsedService service = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg != null && pkg.getServices() != null) {
                for (ParsedService parsedService : pkg.getServices()) {
                    if (Objects.equals(parsedService.className, filter.getClassName())) {
                        service = parsedService;
                    }
                }
            }

            if (service == null) {
                return null;
            }

            if (!sPackageManagerInternal.isEnabledAndMatches(service, mFlags, userId)) {
                return null;
            }

            PackageSetting ps = (PackageSetting) sPackageManagerInternal.getPackageSetting(
                    filter.getPackageName());
            if (ps == null) {
                return null;
            }
            final PackageUserState userState = ps.readUserState(userId);
            ServiceInfo si = PackageInfoUtils.generateServiceInfo(pkg, service, mFlags,
                    userState, userId);
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
            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = filter.hasDefault;
            res.labelRes = filter.labelRes;
            res.nonLocalizedLabel = filter.nonLocalizedLabel;
            res.icon = filter.icon;
            res.system = res.serviceInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            results.sort(RESOLVE_PRIORITY_SORTER);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                ParsedServiceIntentInfo filter) {
            ParsedService service = null;

            AndroidPackage pkg = sPackageManagerInternal.getPackage(filter.getPackageName());
            if (pkg != null && pkg.getServices() != null) {
                for (ParsedService parsedService : pkg.getServices()) {
                    if (Objects.equals(parsedService.className, filter.getClassName())) {
                        service = parsedService;
                    }
                }
            }

            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(service)));
            out.print(' ');
            ComponentName.printShortString(out, filter.getPackageName(), filter.getClassName());
            out.print(" filter ");
            out.print(Integer.toHexString(System.identityHashCode(filter)));
            if (service != null && service.getPermission() != null) {
                out.print(" permission "); out.println(service.getPermission());
            } else {
                out.println();
            }
        }

        @Override
        protected Object filterToLabel(ParsedServiceIntentInfo filter) {
            return filter;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            final ParsedServiceIntentInfo service = (ParsedServiceIntentInfo) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(service)));
            out.print(' ');
            ComponentName.printShortString(out, service.getPackageName(), service.getClassName());
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
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
    }

    /** Generic to create an {@link Iterator} for a data type */
    static class IterGenerator<E> {
        public Iterator<E> generate(ParsedActivityIntentInfo info) {
            return null;
        }
    }

    /** Create an {@link Iterator} for intent actions */
    static class ActionIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedActivityIntentInfo info) {
            return info.actionsIterator();
        }
    }

    /** Create an {@link Iterator} for intent categories */
    static class CategoriesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedActivityIntentInfo info) {
            return info.categoriesIterator();
        }
    }

    /** Create an {@link Iterator} for intent schemes */
    static class SchemesIterGenerator extends IterGenerator<String> {
        @Override
        public Iterator<String> generate(ParsedActivityIntentInfo info) {
            return info.schemesIterator();
        }
    }

    /** Create an {@link Iterator} for intent authorities */
    static class AuthoritiesIterGenerator extends IterGenerator<IntentFilter.AuthorityEntry> {
        @Override
        public Iterator<IntentFilter.AuthorityEntry> generate(ParsedActivityIntentInfo info) {
            return info.authoritiesIterator();
        }
    }
}
