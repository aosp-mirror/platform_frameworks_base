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
package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.compat.PlatformCompat;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * @deprecated This feature is no longer used. Delete this class.
 *
 * Also delete Intnt.(set|get)OriginalIntent.
 */
@Deprecated
public class ComponentAliasResolver {
    private static final String TAG = "ComponentAliasResolver";
    private static final boolean DEBUG = true;

    /**
     * This flag has to be enabled for the "android" package to use component aliases.
     */
    @ChangeId
    @Disabled
    public static final long USE_EXPERIMENTAL_COMPONENT_ALIAS = 196254758L;

    private final Object mLock = new Object();
    private final ActivityManagerService mAm;
    private final Context mContext;

    @GuardedBy("mLock")
    private boolean mEnabledByDeviceConfig;

    @GuardedBy("mLock")
    private boolean mEnabled;

    @GuardedBy("mLock")
    private String mOverrideString;

    @GuardedBy("mLock")
    private final ArrayMap<ComponentName, ComponentName> mFromTo = new ArrayMap<>();

    @GuardedBy("mLock")
    private PlatformCompat mPlatformCompat;

    private static final String OPT_IN_PROPERTY = "com.android.EXPERIMENTAL_COMPONENT_ALIAS_OPT_IN";

    private static final String ALIAS_FILTER_ACTION =
            "com.android.intent.action.EXPERIMENTAL_IS_ALIAS";
    private static final String ALIAS_FILTER_ACTION_ALT =
            "android.intent.action.EXPERIMENTAL_IS_ALIAS";
    private static final String META_DATA_ALIAS_TARGET = "alias_target";

    private static final int PACKAGE_QUERY_FLAGS =
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_ANY_USER
                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.GET_META_DATA;

    public ComponentAliasResolver(ActivityManagerService service) {
        mAm = service;
        mContext = service.mContext;
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    /**
     * When there's any change to packages, we refresh all the aliases.
     * TODO: In the production version, we should update only the changed package.
     */
    final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageModified(String packageName) {
            refresh();
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            refresh();
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            refresh();
        }
    };

    /**
     * Call this on systemRead().
     */
    public void onSystemReady(boolean enabledByDeviceConfig, String overrides) {
        synchronized (mLock) {
            mPlatformCompat = (PlatformCompat) ServiceManager.getService(
                    Context.PLATFORM_COMPAT_SERVICE);
        }
        if (DEBUG) Slog.d(TAG, "Compat listener set.");
        update(enabledByDeviceConfig, overrides);
    }

    /**
     * (Re-)loads aliases from <meta-data> and the device config override.
     */
    public void update(boolean enabledByDeviceConfig, String overrides) {
        synchronized (mLock) {
            if (mPlatformCompat == null) {
                return; // System not ready.
            }
            // Never enable it.
            final boolean enabled = false;
            if (enabled != mEnabled) {
                Slog.i(TAG, (enabled ? "Enabling" : "Disabling") + " component aliases...");
                FgThread.getHandler().post(() -> {
                    // Registering/unregistering a receiver internally takes the AM lock, but AM
                    // calls into this class while holding the AM lock. So do it on a handler to
                    // avoid deadlocks.
                    if (enabled) {
                        mPackageMonitor.register(mAm.mContext, UserHandle.ALL,
                                BackgroundThread.getHandler());
                    } else {
                        mPackageMonitor.unregister();
                    }
                });
            }
            mEnabled = enabled;
            mEnabledByDeviceConfig = enabledByDeviceConfig;
            mOverrideString = overrides;

            if (mEnabled) {
                refreshLocked();
            } else {
                mFromTo.clear();
            }
        }
    }

    private void refresh() {
        synchronized (mLock) {
            update(mEnabledByDeviceConfig, mOverrideString);
        }
    }

    @GuardedBy("mLock")
    private void refreshLocked() {
        if (DEBUG) Slog.d(TAG, "Refreshing aliases...");
        mFromTo.clear();
        loadFromMetadataLocked();
        loadOverridesLocked();
    }

    /**
     * Scans all the "alias" components and inserts the from-to pairs to the map.
     */
    @GuardedBy("mLock")
    private void loadFromMetadataLocked() {
        if (DEBUG) Slog.d(TAG, "Scanning service aliases...");

        // PM.queryInetntXxx() doesn't support "OR" queries, so we search for
        // both the com.android... action and android... action on by one.
        // It's okay if a single component handles both actions because the resulting aliases
        // will be stored in a map and duplicates will naturally be removed.
        loadFromMetadataLockedInner(new Intent(ALIAS_FILTER_ACTION_ALT));
        loadFromMetadataLockedInner(new Intent(ALIAS_FILTER_ACTION));
    }

    private void loadFromMetadataLockedInner(Intent i) {
        final List<ResolveInfo> services = mContext.getPackageManager().queryIntentServicesAsUser(
                i, PACKAGE_QUERY_FLAGS, UserHandle.USER_SYSTEM);

        extractAliasesLocked(services);

        if (DEBUG) Slog.d(TAG, "Scanning receiver aliases...");
        final List<ResolveInfo> receivers = mContext.getPackageManager()
                .queryBroadcastReceiversAsUser(i, PACKAGE_QUERY_FLAGS, UserHandle.USER_SYSTEM);

        extractAliasesLocked(receivers);

        // TODO: Scan for other component types as well.
    }

    /**
     * Make sure a given package is opted into component alias, by having a
     * "com.android.EXPERIMENTAL_COMPONENT_ALIAS_OPT_IN" property set to true in the manifest.
     *
     * The implementation isn't optimized -- in every call we scan the package's properties,
     * even thought we're likely going to call it with the same packages multiple times.
     * But that's okay since this feature is experimental, and this code path won't be called
     * until explicitly enabled.
     */
    @GuardedBy("mLock")
    private boolean isEnabledForPackageLocked(String packageName) {
        boolean enabled = false;
        try {
            final Property p = mContext.getPackageManager().getProperty(
                    OPT_IN_PROPERTY, packageName);
            enabled = p.getBoolean();
        } catch (NameNotFoundException e) {
        }
        if (!enabled) {
            Slog.w(TAG, "USE_EXPERIMENTAL_COMPONENT_ALIAS not enabled for " + packageName);
        }
        return enabled;
    }

    /**
     * Make sure an alias and its target are the same package, or, the target is in a "sub" package.
     */
    private static boolean validateAlias(ComponentName from, ComponentName to) {
        final String fromPackage = from.getPackageName();
        final String toPackage = to.getPackageName();

        if (Objects.equals(fromPackage, toPackage)) { // Same package?
            return true;
        }
        if (toPackage.startsWith(fromPackage + ".")) { // Prefix?
            return true;
        }
        Slog.w(TAG, "Invalid alias: "
                + from.flattenToShortString() + " -> " + to.flattenToShortString());
        return false;
    }

    @GuardedBy("mLock")
    private void validateAndAddAliasLocked(ComponentName from, ComponentName to) {
        if (DEBUG) {
            Slog.d(TAG,
                    "" + from.flattenToShortString() + " -> " + to.flattenToShortString());
        }
        if (!validateAlias(from, to)) {
            return;
        }

        // Make sure both packages have
        if (!isEnabledForPackageLocked(from.getPackageName())
                || !isEnabledForPackageLocked(to.getPackageName())) {
            return;
        }

        mFromTo.put(from, to);
    }

    @GuardedBy("mLock")
    private void extractAliasesLocked(List<ResolveInfo> components) {
        for (ResolveInfo ri : components) {
            final ComponentInfo ci = ri.getComponentInfo();
            final ComponentName from = ci.getComponentName();
            final ComponentName to = unflatten(ci.metaData.getString(META_DATA_ALIAS_TARGET));
            if (to == null) {
                continue;
            }
            validateAndAddAliasLocked(from, to);
        }
    }

    /**
     * Parses an "override" string and inserts the from-to pairs to the map.
     *
     * The format is:
     * ALIAS-COMPONENT-1 ":" TARGET-COMPONENT-1 ( "," ALIAS-COMPONENT-2 ":" TARGET-COMPONENT-2 )*
     */
    @GuardedBy("mLock")
    private void loadOverridesLocked() {
        if (DEBUG) Slog.d(TAG, "Loading aliases overrides ...");
        for (String line : mOverrideString.split("\\,+")) {
            final String[] fields = line.split("\\:+", 2);
            if (TextUtils.isEmpty(fields[0])) {
                continue;
            }
            final ComponentName from = unflatten(fields[0]);
            if (from == null) {
                continue;
            }

            if (fields.length == 1) {
                if (DEBUG) Slog.d(TAG, "" + from.flattenToShortString() + " [removed]");
                mFromTo.remove(from);
            } else {
                final ComponentName to = unflatten(fields[1]);
                if (to == null) {
                    continue;
                }

                validateAndAddAliasLocked(from, to);
            }
        }
    }

    private static ComponentName unflatten(String name) {
        final ComponentName cn = ComponentName.unflattenFromString(name);
        if (cn != null) {
            return cn;
        }
        Slog.e(TAG, "Invalid component name detected: " + name);
        return null;
    }

    /**
     * Dump the aliases for dumpsys / bugrports.
     */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("ACTIVITY MANAGER COMPONENT-ALIAS (dumpsys activity component-alias)");
            pw.print("  Enabled: "); pw.println(mEnabled);

            pw.println("  Aliases:");
            for (int i = 0; i < mFromTo.size(); i++) {
                ComponentName from = mFromTo.keyAt(i);
                ComponentName to = mFromTo.valueAt(i);
                pw.print("    ");
                pw.print(from.flattenToShortString());
                pw.print(" -> ");
                pw.print(to.flattenToShortString());
                pw.println();
            }
            pw.println();
        }
    }

    /**
     * Contains alias resolution information.
     */
    public static class Resolution<T> {
        /** "From" component. Null if component alias is disabled. */
        @Nullable
        public final T source;

        /** "To" component. Null if component alias is disabled, or the source isn't an alias. */
        @Nullable
        public final T resolved;

        public Resolution(T source, T resolved) {
            this.source = source;
            this.resolved = resolved;
        }

        @Nullable
        public boolean isAlias() {
            return this.resolved != null;
        }

        @Nullable
        public T getAlias() {
            return isAlias() ? source : null;
        }

        @Nullable
        public T getTarget() {
            return isAlias() ? resolved : null;
        }
    }

    @NonNull
    public Resolution<ComponentName> resolveComponentAlias(
            @NonNull Supplier<ComponentName> aliasSupplier) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!mEnabled) {
                    return new Resolution<>(null, null);
                }

                final ComponentName alias = aliasSupplier.get();
                final ComponentName target = mFromTo.get(alias);

                if (target != null) {
                    if (DEBUG) {
                        Exception stacktrace = null;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            stacktrace = new RuntimeException("STACKTRACE");
                        }
                        Slog.d(TAG, "Alias resolved: " + alias.flattenToShortString()
                                + " -> " + target.flattenToShortString(), stacktrace);
                    }
                }
                return new Resolution<>(alias, target);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    public Resolution<ComponentName> resolveService(
            @NonNull Intent service, @Nullable String resolvedType,
            int packageFlags, int userId, int callingUid) {
        Resolution<ComponentName> result = resolveComponentAlias(() -> {
            PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);

            ResolveInfo rInfo = pmi.resolveService(service,
                    resolvedType, packageFlags, userId, callingUid);
            ServiceInfo sInfo = rInfo != null ? rInfo.serviceInfo : null;
            if (sInfo == null) {
                return null; // Service not found.
            }
            return new ComponentName(sInfo.applicationInfo.packageName, sInfo.name);
        });

        // TODO: To make it consistent with resolveReceiver(), let's ensure the target service
        // is resolvable, and if not, return null.

        if (result != null && result.isAlias()) {
            // It's an alias. Keep the original intent, and rewrite it.
            service.setOriginalIntent(new Intent(service));

            service.setPackage(null);
            service.setComponent(result.getTarget());
        }
        return result;
    }

    @Nullable
    public Resolution<ResolveInfo> resolveReceiver(@NonNull Intent intent,
            @NonNull ResolveInfo receiver, @Nullable String resolvedType,
            long packageFlags, int userId, int callingUid, boolean forSend) {
        // Resolve this alias.
        final Resolution<ComponentName> resolution = resolveComponentAlias(() ->
                receiver.activityInfo.getComponentName());
        final ComponentName target = resolution.getTarget();
        if (target == null) {
            return new Resolution<>(receiver, null); // It's not an alias.
        }

        // Convert the target component name to a ResolveInfo.

        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);

        // Rewrite the intent to search the target intent.
        // - We don't actually rewrite the intent we deliver to the receiver here, which is what
        //  resolveService() does, because this intent many be send to other receivers as well.
        // - But we don't have to do that here either, because the actual receiver component
        //   will be set in BroadcastQueue anyway, before delivering the intent to each receiver.
        // - However, we're not able to set the original intent either, for the time being.
        Intent i = new Intent(intent);
        i.setPackage(null);
        i.setComponent(resolution.getTarget());

        List<ResolveInfo> resolved = pmi.queryIntentReceivers(
                i, resolvedType, packageFlags, callingUid, userId, forSend);
        if (resolved == null || resolved.size() == 0) {
            // Target component not found.
            Slog.w(TAG, "Alias target " + target.flattenToShortString() + " not found");
            return null;
        }
        return new Resolution<>(receiver, resolved.get(0));
    }
}
