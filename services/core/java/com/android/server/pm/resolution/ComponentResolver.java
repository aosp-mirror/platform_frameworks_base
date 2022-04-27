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

import static android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER;

import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
import com.android.server.pm.Computer;
import com.android.server.pm.PackageManagerException;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.UserNeedsBadgingCache;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUtils;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.component.ComponentMutateUtils;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedProviderImpl;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Resolves all Android component types [activities, services, providers and receivers]. */
public class ComponentResolver extends ComponentResolverLocked implements
        Snappable<ComponentResolverApi> {
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

    public static final Comparator<ResolveInfo> RESOLVE_PRIORITY_SORTER = (r1, r2) -> {
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

    /** Whether or not processing protected filters should be deferred. */
    boolean mDeferProtectedFilters = true;

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
    List<Pair<ParsedMainComponent, ParsedIntentInfo>> mProtectedFilters;

    public ComponentResolver(@NonNull UserManagerService userManager,
            @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
        super(userManager);
        mActivities = new ActivityIntentResolver(userManager, userNeedsBadgingCache);
        mProviders = new ProviderIntentResolver(userManager);
        mReceivers = new ReceiverIntentResolver(userManager, userNeedsBadgingCache);
        mServices = new ServiceIntentResolver(userManager);
        mProvidersByAuthority = new ArrayMap<>();
        mDeferProtectedFilters = true;

        mSnapshot = new SnapshotCache<>(this, this) {
                @Override
                public ComponentResolverApi createSnapshot() {
                    synchronized (mLock) {
                        return new ComponentResolverSnapshot(ComponentResolver.this,
                                userNeedsBadgingCache);
                    }
                }};
    }

    final SnapshotCache<ComponentResolverApi> mSnapshot;

    /**
     * Create a snapshot.
     */
    public ComponentResolverApi snapshot() {
        return mSnapshot.snapshot();
    }

    /** Add all components defined in the given package to the internal structures. */
    public void addAllComponents(AndroidPackage pkg, boolean chatty,
            @Nullable String setupWizardPackage, @NonNull Computer computer) {
        final ArrayList<Pair<ParsedActivity, ParsedIntentInfo>> newIntents = new ArrayList<>();
        synchronized (mLock) {
            addActivitiesLocked(computer, pkg, newIntents, chatty);
            addReceiversLocked(computer, pkg, chatty);
            addProvidersLocked(computer, pkg, chatty);
            addServicesLocked(computer, pkg, chatty);
            onChanged();
        }

        for (int i = newIntents.size() - 1; i >= 0; --i) {
            final Pair<ParsedActivity, ParsedIntentInfo> pair = newIntents.get(i);
            final PackageStateInternal disabledPkgSetting = computer
                    .getDisabledSystemPackage(pair.first.getPackageName());
            final AndroidPackage disabledPkg =
                    disabledPkgSetting == null ? null : disabledPkgSetting.getPkg();
            final List<ParsedActivity> systemActivities =
                    disabledPkg != null ? disabledPkg.getActivities() : null;
            adjustPriority(computer, systemActivities, pair.first, pair.second, setupWizardPackage);
            onChanged();
        }
    }

    /** Removes all components defined in the given package from the internal structures. */
    public void removeAllComponents(AndroidPackage pkg, boolean chatty) {
        synchronized (mLock) {
            removeAllComponentsLocked(pkg, chatty);
            onChanged();
        }
    }

    /**
     * Reprocess any protected filters that have been deferred. At this point, we've scanned
     * all of the filters defined on the /system partition and know the special components.
     */
    public void fixProtectedFilterPriorities(@Nullable String setupWizardPackage) {
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

            if (DEBUG_FILTERS && setupWizardPackage == null) {
                Slog.i(TAG, "No setup wizard;"
                        + " All protected intents capped to priority 0");
            }
            for (int i = protectedFilters.size() - 1; i >= 0; --i) {
                final Pair<ParsedMainComponent, ParsedIntentInfo> pair = protectedFilters.get(i);
                ParsedMainComponent component = pair.first;
                ParsedIntentInfo intentInfo = pair.second;
                IntentFilter filter = intentInfo.getIntentFilter();
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

    @GuardedBy("mLock")
    private void addActivitiesLocked(@NonNull Computer computer, AndroidPackage pkg,
            List<Pair<ParsedActivity, ParsedIntentInfo>> newIntents, boolean chatty) {
        final int activitiesSize = ArrayUtils.size(pkg.getActivities());
        StringBuilder r = null;
        for (int i = 0; i < activitiesSize; i++) {
            ParsedActivity a = pkg.getActivities().get(i);
            mActivities.addActivity(computer, a, "activity", newIntents);
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
    private void addProvidersLocked(@NonNull Computer computer, AndroidPackage pkg, boolean chatty) {
        final int providersSize = ArrayUtils.size(pkg.getProviders());
        StringBuilder r = null;
        for (int i = 0; i < providersSize; i++) {
            ParsedProvider p = pkg.getProviders().get(i);
            mProviders.addProvider(computer, p);
            if (p.getAuthority() != null) {
                String[] names = p.getAuthority().split(";");

                // TODO(b/135203078): Remove this mutation
                ComponentMutateUtils.setAuthority(p, null);
                for (int j = 0; j < names.length; j++) {
                    if (j == 1 && p.isSyncable()) {
                        // We only want the first authority for a provider to possibly be
                        // syncable, so if we already added this provider using a different
                        // authority clear the syncable flag. We copy the provider before
                        // changing it because the mProviders object contains a reference
                        // to a provider that we don't want to change.
                        // Only do this for the second authority since the resulting provider
                        // object can be the same for all future authorities for this provider.
                        p = new ParsedProviderImpl(p);
                        ComponentMutateUtils.setSyncable(p, false);
                    }
                    if (!mProvidersByAuthority.containsKey(names[j])) {
                        mProvidersByAuthority.put(names[j], p);
                        if (p.getAuthority() == null) {
                            ComponentMutateUtils.setAuthority(p, names[j]);
                        } else {
                            ComponentMutateUtils.setAuthority(p, p.getAuthority() + ";" + names[j]);
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
    private void addReceiversLocked(@NonNull Computer computer, AndroidPackage pkg, boolean chatty) {
        final int receiversSize = ArrayUtils.size(pkg.getReceivers());
        StringBuilder r = null;
        for (int i = 0; i < receiversSize; i++) {
            ParsedActivity a = pkg.getReceivers().get(i);
            mReceivers.addActivity(computer, a, "receiver", null);
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
    private void addServicesLocked(@NonNull Computer computer, AndroidPackage pkg, boolean chatty) {
        final int servicesSize = ArrayUtils.size(pkg.getServices());
        StringBuilder r = null;
        for (int i = 0; i < servicesSize; i++) {
            ParsedService s = pkg.getServices().get(i);
            mServices.addService(computer, s);
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
            Function<IntentFilter, Iterator<T>> generator, Iterator<T> searchIterator) {
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
                final Iterator<T> intentSelectionIter =
                        generator.apply(intentInfo.getIntentFilter());
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

    private static boolean isProtectedAction(IntentFilter filter) {
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
    private void adjustPriority(@NonNull Computer computer, List<ParsedActivity> systemActivities,
            ParsedActivity activity, ParsedIntentInfo intentInfo, String setupWizardPackage) {
        // nothing to do; priority is fine as-is
        IntentFilter intentFilter = intentInfo.getIntentFilter();
        if (intentFilter.getPriority() <= 0) {
            return;
        }

        String packageName = activity.getPackageName();
        AndroidPackage pkg = computer.getPackage(packageName);

        final boolean privilegedApp = pkg.isPrivileged();
        String className = activity.getClassName();
        if (!privilegedApp) {
            // non-privileged applications can never define a priority >0
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Non-privileged app; cap priority to 0;"
                        + " package: " + packageName
                        + " activity: " + className
                        + " origPrio: " + intentFilter.getPriority());
            }
            intentFilter.setPriority(0);
            return;
        }

        if (isProtectedAction(intentFilter)) {
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
                mProtectedFilters.add(Pair.create(activity, intentInfo));
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Protected action; save for later;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
            } else {
                if (DEBUG_FILTERS && setupWizardPackage == null) {
                    Slog.i(TAG, "No setup wizard;"
                            + " All protected intents capped to priority 0");
                }
                if (packageName.equals(setupWizardPackage)) {
                    if (DEBUG_FILTERS) {
                        Slog.i(TAG, "Found setup wizard;"
                                + " allow priority " + intentFilter.getPriority() + ";"
                                + " package: " + packageName
                                + " activity: " + className
                                + " priority: " + intentFilter.getPriority());
                    }
                    // setup wizard gets whatever it wants
                    return;
                }
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Protected action; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
                intentFilter.setPriority(0);
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
                        + " origPrio: " + intentFilter.getPriority());
            }
            intentFilter.setPriority(0);
            return;
        }

        // found activity, now check for filter equivalence

        // a shallow copy is enough; we modify the list, not its contents
        final List<ParsedIntentInfo> intentListCopy =
                new ArrayList<>(foundActivity.getIntents());

        // find matching action subsets
        final Iterator<String> actionsIterator = intentFilter.actionsIterator();
        if (actionsIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::actionsIterator, actionsIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched action; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
                intentFilter.setPriority(0);
                return;
            }
        }

        // find matching category subsets
        final Iterator<String> categoriesIterator = intentFilter.categoriesIterator();
        if (categoriesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::categoriesIterator,
                    categoriesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched category; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
                intentFilter.setPriority(0);
                return;
            }
        }

        // find matching schemes subsets
        final Iterator<String> schemesIterator = intentFilter.schemesIterator();
        if (schemesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::schemesIterator, schemesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched scheme; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
                intentFilter.setPriority(0);
                return;
            }
        }

        // find matching authorities subsets
        final Iterator<IntentFilter.AuthorityEntry> authoritiesIterator =
                intentFilter.authoritiesIterator();
        if (authoritiesIterator != null) {
            getIntentListSubset(intentListCopy, IntentFilter::authoritiesIterator,
                    authoritiesIterator);
            if (intentListCopy.size() == 0) {
                // no more intents to match; we're not equivalent
                if (DEBUG_FILTERS) {
                    Slog.i(TAG, "Mismatched authority; cap priority to 0;"
                            + " package: " + packageName
                            + " activity: " + className
                            + " origPrio: " + intentFilter.getPriority());
                }
                intentFilter.setPriority(0);
                return;
            }
        }

        // we found matching filter(s); app gets the max priority of all intents
        int cappedPriority = 0;
        for (int i = intentListCopy.size() - 1; i >= 0; --i) {
            cappedPriority = Math.max(cappedPriority,
                    intentListCopy.get(i).getIntentFilter().getPriority());
        }
        if (intentFilter.getPriority() > cappedPriority) {
            if (DEBUG_FILTERS) {
                Slog.i(TAG, "Found matching filter(s);"
                        + " cap priority to " + cappedPriority + ";"
                        + " package: " + packageName
                        + " activity: " + className
                        + " origPrio: " + intentFilter.getPriority());
            }
            intentFilter.setPriority(cappedPriority);
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

    /** Asserts none of the providers defined in the given package haven't already been defined. */
    public void assertProvidersNotDefined(@NonNull AndroidPackage pkg)
            throws PackageManagerException {
        synchronized (mLock) {
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
                            // if installing over the same already-installed package,this is ok
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
    }

    private abstract static class MimeGroupsAwareIntentResolver<F extends Pair<?
            extends ParsedComponent, ParsedIntentInfo>, R>
            extends IntentResolver<F, R> {
        private final ArrayMap<String, F[]> mMimeGroupToFilter = new ArrayMap<>();
        private boolean mIsUpdatingMimeGroup = false;

        @NonNull
        protected final UserManagerService mUserManager;

        // Default constructor
        MimeGroupsAwareIntentResolver(@NonNull UserManagerService userManager) {
            mUserManager = userManager;
        }

        // Copy constructor used in creating snapshots
        MimeGroupsAwareIntentResolver(MimeGroupsAwareIntentResolver<F, R> orig,
                @NonNull UserManagerService userManager) {
            mUserManager = userManager;
            copyFrom(orig);
            copyInto(mMimeGroupToFilter, orig.mMimeGroupToFilter);
            mIsUpdatingMimeGroup = orig.mIsUpdatingMimeGroup;
        }

        @Override
        public void addFilter(@Nullable PackageDataSnapshot snapshot, F f) {
            IntentFilter intentFilter = getIntentFilter(f);
            // We assume Computer is available for this class and all subclasses. Because this class
            // uses subclass method override to handle logic, the Computer parameter must be in the
            // base, leading to this odd nullability.
            applyMimeGroups((Computer) snapshot, f);
            super.addFilter(snapshot, f);

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
        public boolean updateMimeGroup(@NonNull Computer computer, String packageName,
                String mimeGroup) {
            F[] filters = mMimeGroupToFilter.get(mimeGroup);
            int n = filters != null ? filters.length : 0;

            mIsUpdatingMimeGroup = true;
            boolean hasChanges = false;
            F filter;
            for (int i = 0; i < n && (filter = filters[i]) != null; i++) {
                if (isPackageForFilter(packageName, filter)) {
                    hasChanges |= updateFilter(computer, filter);
                }
            }
            mIsUpdatingMimeGroup = false;
            return hasChanges;
        }

        private boolean updateFilter(@NonNull Computer computer, F f) {
            IntentFilter filter = getIntentFilter(f);
            List<String> oldTypes = filter.dataTypes();
            removeFilter(f);
            addFilter(computer, f);
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

        private void applyMimeGroups(@NonNull Computer computer, F f) {
            IntentFilter filter = getIntentFilter(f);

            for (int i = filter.countMimeGroups() - 1; i >= 0; i--) {
                final PackageStateInternal packageState = computer.getPackageStateInternal(
                        f.first.getPackageName());

                Collection<String> mimeTypes = packageState == null
                        ? Collections.emptyList() : packageState.getMimeGroups()
                        .get(filter.getMimeGroup(i));

                for (String mimeType : mimeTypes) {
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

        @Override
        protected boolean isFilterStopped(@Nullable PackageStateInternal packageState,
                @UserIdInt int userId) {
            if (!mUserManager.exists(userId)) {
                return true;
            }

            if (packageState == null || packageState.getPkg() == null) {
                return false;
            }

            // System apps are never considered stopped for purposes of
            // filtering, because there may be no way for the user to
            // actually re-launch them.
            return !packageState.isSystem()
                    && packageState.getUserStateOrDefault(userId).isStopped();
        }
    }

    public static class ActivityIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedActivity, ParsedIntentInfo>, ResolveInfo> {

        @NonNull
        private UserNeedsBadgingCache mUserNeedsBadging;

        // Default constructor
        ActivityIntentResolver(@NonNull UserManagerService userManager,
                @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
            super(userManager);
            mUserNeedsBadging = userNeedsBadgingCache;
        }

        // Copy constructor used in creating snapshots
        ActivityIntentResolver(@NonNull ActivityIntentResolver orig,
                @NonNull UserManagerService userManager,
                @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
            super(orig, userManager);
            mActivities.putAll(orig.mActivities);
            mUserNeedsBadging = userNeedsBadgingCache;
        }

        @Override
        public List<ResolveInfo> queryIntent(@NonNull PackageDataSnapshot snapshot, Intent intent,
                String resolvedType, boolean defaultOnly, @UserIdInt int userId) {
            if (!mUserManager.exists(userId)) return null;
            long flags = (defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0);
            return super.queryIntent(snapshot, intent, resolvedType, defaultOnly, userId, flags);
        }

        List<ResolveInfo> queryIntent(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            return super.queryIntent(computer, intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId, flags);
        }

        List<ResolveInfo> queryIntentForPackage(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, List<ParsedActivity> packageActivities,
                int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            if (packageActivities == null) {
                return Collections.emptyList();
            }
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
            return super.queryIntentFromList(computer, intent, resolvedType,
                    defaultOnly, listCut, userId, flags);
        }

        protected void addActivity(@NonNull Computer computer, ParsedActivity a, String type,
                List<Pair<ParsedActivity, ParsedIntentInfo>> newIntents) {
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + ":");
                Log.v(TAG, "    Class=" + a.getName());
            }
            final int intentsSize = a.getIntents().size();
            for (int j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = a.getIntents().get(j);
                IntentFilter intentFilter = intent.getIntentFilter();
                if (newIntents != null && "activity".equals(type)) {
                    newIntents.add(Pair.create(a, intent));
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intentFilter.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.getName());
                }
                addFilter(computer, Pair.create(a, intent));
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
                IntentFilter intentFilter = intent.getIntentFilter();
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
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
        protected ResolveInfo newResult(@NonNull Computer computer,
                Pair<ParsedActivity, ParsedIntentInfo> pair, int match, int userId,
                long customFlags) {
            ParsedActivity activity = pair.first;
            ParsedIntentInfo info = pair.second;
            IntentFilter intentFilter = info.getIntentFilter();

            if (!mUserManager.exists(userId)) {
                if (DEBUG) {
                    log("User doesn't exist", info, match, userId);
                }
                return null;
            }

            final PackageStateInternal packageState =
                    computer.getPackageStateInternal(activity.getPackageName());
            if (packageState == null || packageState.getPkg() == null
                    || !PackageStateUtils.isEnabledAndMatches(packageState, activity, customFlags,
                    userId)) {
                if (DEBUG) {
                    log("!PackageManagerInternal.isEnabledAndMatches; flags="
                            + DebugUtils.flagsToString(PackageManager.class, "MATCH_", customFlags),
                            info, match, userId);
                }
                return null;
            }
            final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
            ActivityInfo ai = PackageInfoUtils.generateActivityInfo(packageState.getPkg(), activity,
                    customFlags, userState, userId, packageState);
            if (ai == null) {
                if (DEBUG) {
                    log("Failed to create ActivityInfo based on " + activity, info, match,
                            userId);
                }
                return null;
            }
            final boolean matchExplicitlyVisibleOnly =
                    (customFlags & PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY) != 0;
            final boolean matchVisibleToInstantApp =
                    (customFlags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean componentVisible =
                    matchVisibleToInstantApp
                    && intentFilter.isVisibleToInstantApp()
                    && (!matchExplicitlyVisibleOnly
                            || intentFilter.isExplicitlyVisibleToInstantApp());
            final boolean matchInstantApp = (customFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to ephemeral apps
            if (matchVisibleToInstantApp && !(componentVisible || userState.isInstantApp())) {
                if (DEBUG) {
                    log("Filter(s) not visible to ephemeral apps"
                            + "; matchVisibleToInstantApp=" + matchVisibleToInstantApp
                            + "; matchInstantApp=" + matchInstantApp
                            + "; info.isVisibleToInstantApp()="
                                    + intentFilter.isVisibleToInstantApp()
                            + "; matchExplicitlyVisibleOnly=" + matchExplicitlyVisibleOnly
                            + "; info.isExplicitlyVisibleToInstantApp()="
                                    + intentFilter.isExplicitlyVisibleToInstantApp(),
                            info, match, userId);
                }
                return null;
            }
            // throw out instant app filters if we're not explicitly requesting them
            if (!matchInstantApp && userState.isInstantApp()) {
                if (DEBUG) {
                    log("Instant app filter is not explicitly requested", info, match, userId);
                }
                return null;
            }
            // throw out instant app filters if updates are available; will trigger
            // instant app resolution
            if (userState.isInstantApp() && packageState.isUpdateAvailable()) {
                if (DEBUG) {
                    log("Instant app update is available", info, match, userId);
                }
                return null;
            }
            final ResolveInfo res =
                    new ResolveInfo(intentFilter.hasCategory(Intent.CATEGORY_BROWSABLE));
            res.activityInfo = ai;
            if ((customFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = intentFilter;
            }
            res.handleAllWebDataURI = intentFilter.handleAllWebDataURI();
            res.priority = intentFilter.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.isHasDefault();
            res.labelRes = info.getLabelRes();
            res.nonLocalizedLabel = info.getNonLocalizedLabel();
            if (mUserNeedsBadging.get(userId)) {
                res.noResourceId = true;
            } else {
                res.icon = info.getIcon();
            }
            res.iconResourceId = info.getIcon();
            res.system = res.activityInfo.applicationInfo.isSystemApp();
            res.isInstantAppAvailable = userState.isInstantApp();
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
            return input.second.getIntentFilter();
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
    }

    // Both receivers and activities share a class, but point to different get methods
    public static final class ReceiverIntentResolver extends ActivityIntentResolver {

        // Default constructor
        ReceiverIntentResolver(@NonNull UserManagerService userManager,
                @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
            super(userManager, userNeedsBadgingCache);
        }

        // Copy constructor used in creating snapshots
        ReceiverIntentResolver(@NonNull ReceiverIntentResolver orig,
                @NonNull UserManagerService userManager,
                @NonNull UserNeedsBadgingCache userNeedsBadgingCache) {
            super(orig, userManager, userNeedsBadgingCache);
        }

        @Override
        protected List<ParsedActivity> getResolveList(AndroidPackage pkg) {
            return pkg.getReceivers();
        }
    }

    public static final class ProviderIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedProvider, ParsedIntentInfo>, ResolveInfo> {
        // Default constructor
        ProviderIntentResolver(@NonNull UserManagerService userManager) {
            super(userManager);
        }

        // Copy constructor used in creating snapshots
        ProviderIntentResolver(@NonNull ProviderIntentResolver orig,
                @NonNull UserManagerService userManager) {
            super(orig, userManager);
            mProviders.putAll(orig.mProviders);
        }

        @Override
        public List<ResolveInfo> queryIntent(@NonNull PackageDataSnapshot snapshot, Intent intent,
                String resolvedType, boolean defaultOnly, @UserIdInt int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            long flags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(snapshot, intent, resolvedType, defaultOnly, userId, flags);
        }

        @Nullable
        List<ResolveInfo> queryIntent(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            return super.queryIntent(computer, intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId, flags);
        }

        @Nullable
        List<ResolveInfo> queryIntentForPackage(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, List<ParsedProvider> packageProviders,
                int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            if (packageProviders == null) {
                return Collections.emptyList();
            }
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
            return super.queryIntentFromList(computer, intent, resolvedType,
                    defaultOnly, listCut, userId, flags);
        }

        void addProvider(@NonNull Computer computer, ParsedProvider p) {
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
                IntentFilter intentFilter = intent.getIntentFilter();
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intentFilter.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.getName());
                }
                addFilter(computer, Pair.create(p, intent));
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
                IntentFilter intentFilter = intent.getIntentFilter();
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
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
        protected boolean isPackageForFilter(String packageName,
                Pair<ParsedProvider, ParsedIntentInfo> info) {
            return packageName.equals(info.first.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(@NonNull Computer computer,
                Pair<ParsedProvider, ParsedIntentInfo> pair, int match, int userId,
                long customFlags) {
            if (!mUserManager.exists(userId)) {
                return null;
            }

            ParsedProvider provider = pair.first;
            ParsedIntentInfo intentInfo = pair.second;
            IntentFilter filter = intentInfo.getIntentFilter();

            PackageStateInternal packageState =
                    computer.getPackageStateInternal(provider.getPackageName());
            if (packageState == null || packageState.getPkg() == null
                    || !PackageStateUtils.isEnabledAndMatches(packageState, provider, customFlags,
                    userId)) {
                return null;
            }

            final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
            final boolean matchVisibleToInstantApp = (customFlags
                    & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (customFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to instant applications
            if (matchVisibleToInstantApp
                    && !(filter.isVisibleToInstantApp() || userState.isInstantApp())) {
                return null;
            }
            // throw out instant application filters if we're not explicitly requesting them
            if (!isInstantApp && userState.isInstantApp()) {
                return null;
            }
            // throw out instant application filters if updates are available; will trigger
            // instant application resolution
            if (userState.isInstantApp() && packageState.isUpdateAvailable()) {
                return null;
            }
            final ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(
                    packageState.getPkg(), customFlags, userState, userId, packageState);
            if (appInfo == null) {
                return null;
            }
            ProviderInfo pi = PackageInfoUtils.generateProviderInfo(packageState.getPkg(), provider,
                    customFlags, userState, appInfo, userId, packageState);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((customFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = filter.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = intentInfo.isHasDefault();
            res.labelRes = intentInfo.getLabelRes();
            res.nonLocalizedLabel = intentInfo.getNonLocalizedLabel();
            res.icon = intentInfo.getIcon();
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
            return input.second.getIntentFilter();
        }

        final ArrayMap<ComponentName, ParsedProvider> mProviders = new ArrayMap<>();
    }

    public static final class ServiceIntentResolver
            extends MimeGroupsAwareIntentResolver<Pair<ParsedService, ParsedIntentInfo>, ResolveInfo> {
        // Default constructor
        ServiceIntentResolver(@NonNull UserManagerService userManager) {
            super(userManager);
        }

        // Copy constructor used in creating snapshots
        ServiceIntentResolver(@NonNull ServiceIntentResolver orig,
                @NonNull UserManagerService userManager) {
            super(orig, userManager);
            mServices.putAll(orig.mServices);
        }

        @Override
        public List<ResolveInfo> queryIntent(@NonNull PackageDataSnapshot snapshot, Intent intent,
                String resolvedType, boolean defaultOnly, @UserIdInt int userId) {
            if (!mUserManager.exists(userId)) {
                return null;
            }
            long flags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(snapshot, intent, resolvedType, defaultOnly, userId, flags);
        }

        List<ResolveInfo> queryIntent(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, int userId) {
            if (!mUserManager.exists(userId)) return null;
            return super.queryIntent(computer, intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId, flags);
        }

        List<ResolveInfo> queryIntentForPackage(@NonNull Computer computer, Intent intent,
                String resolvedType, long flags, List<ParsedService> packageServices, int userId) {
            if (!mUserManager.exists(userId)) return null;
            if (packageServices == null) {
                return Collections.emptyList();
            }
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
            return super.queryIntentFromList(computer, intent, resolvedType,
                    defaultOnly, listCut, userId, flags);
        }

        void addService(@NonNull Computer computer, ParsedService s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  service:");
                Log.v(TAG, "    Class=" + s.getName());
            }
            final int intentsSize = s.getIntents().size();
            int j;
            for (j = 0; j < intentsSize; j++) {
                ParsedIntentInfo intent = s.getIntents().get(j);
                IntentFilter intentFilter = intent.getIntentFilter();
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intentFilter.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.getName());
                }
                addFilter(computer, Pair.create(s, intent));
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
                IntentFilter intentFilter = intent.getIntentFilter();
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intentFilter.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
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
        protected boolean isPackageForFilter(String packageName,
                Pair<ParsedService, ParsedIntentInfo> info) {
            return packageName.equals(info.first.getPackageName());
        }

        @Override
        protected ResolveInfo newResult(@NonNull Computer computer,
                Pair<ParsedService, ParsedIntentInfo> pair, int match, int userId,
                long customFlags) {
            if (!mUserManager.exists(userId)) return null;

            ParsedService service = pair.first;
            ParsedIntentInfo intentInfo = pair.second;
            IntentFilter filter = intentInfo.getIntentFilter();

            final PackageStateInternal packageState = computer.getPackageStateInternal(
                    service.getPackageName());
            if (packageState == null || packageState.getPkg() == null
                    || !PackageStateUtils.isEnabledAndMatches(packageState, service, customFlags,
                    userId)) {
                return null;
            }

            final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
            ServiceInfo si = PackageInfoUtils.generateServiceInfo(packageState.getPkg(), service,
                    customFlags, userState, userId, packageState);
            if (si == null) {
                return null;
            }
            final boolean matchVisibleToInstantApp =
                    (customFlags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
            final boolean isInstantApp = (customFlags & PackageManager.MATCH_INSTANT) != 0;
            // throw out filters that aren't visible to ephemeral apps
            if (matchVisibleToInstantApp
                    && !(filter.isVisibleToInstantApp() || userState.isInstantApp())) {
                return null;
            }
            // throw out ephemeral filters if we're not explicitly requesting them
            if (!isInstantApp && userState.isInstantApp()) {
                return null;
            }
            // throw out instant app filters if updates are available; will trigger
            // instant app resolution
            if (userState.isInstantApp() && packageState.isUpdateAvailable()) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = si;
            if ((customFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = filter.getPriority();
            // TODO(b/135203078): This field was unwritten and does nothing
//            res.preferredOrder = pkg.getPreferredOrder();
            res.match = match;
            res.isDefault = intentInfo.isHasDefault();
            res.labelRes = intentInfo.getLabelRes();
            res.nonLocalizedLabel = intentInfo.getNonLocalizedLabel();
            res.icon = intentInfo.getIcon();
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
            return input.second.getIntentFilter();
        }

        // Keys are String (activity class name), values are Activity.
        final ArrayMap<ComponentName, ParsedService> mServices = new ArrayMap<>();
    }

    public static final class InstantAppIntentResolver
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

        @NonNull
        private final UserManagerService mUserManager;

        public InstantAppIntentResolver(@NonNull UserManagerService userManager) {
            mUserManager = userManager;
        }

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
        protected AuxiliaryResolveInfo.AuxiliaryFilter newResult(@NonNull Computer computer,
                AuxiliaryResolveInfo.AuxiliaryFilter responseObj, int match, int userId,
                long customFlags) {
            if (!mUserManager.exists(userId)) {
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

    /**
     * Removes MIME type from the group, by delegating to IntentResolvers
     * @return true if any intent filters were changed due to this update
     */
    public boolean updateMimeGroup(@NonNull Computer computer, String packageName, String group) {
        boolean hasChanges = false;
        synchronized (mLock) {
            hasChanges |= mActivities.updateMimeGroup(computer, packageName, group);
            hasChanges |= mProviders.updateMimeGroup(computer, packageName, group);
            hasChanges |= mReceivers.updateMimeGroup(computer, packageName, group);
            hasChanges |= mServices.updateMimeGroup(computer, packageName, group);
            if (hasChanges) {
                onChanged();
            }
        }
        return hasChanges;
    }
}
