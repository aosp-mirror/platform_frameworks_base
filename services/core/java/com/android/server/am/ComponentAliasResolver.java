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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Manages and handles component aliases, which is an experimental feature.
 *
 * For now, this is an experimental feature to evaluate feasibility, so the implementation is
 * "quick & dirty". For example, to define aliases, we use a regular intent filter and meta-data
 * in the manifest, instead of adding proper tags/attributes to AndroidManifest.xml.
 *
 * Also, for now, aliases can be defined across any packages, but in the final version, there'll
 * be restrictions:
 * - We probably should only allow either privileged or preinstalled apps.
 * - Aliases can only be defined across packages that are atomically installed, and signed with the
 *   same key.
 */
public class ComponentAliasResolver {
    private static final String TAG = "ComponentAliasResolver";
    private static final boolean DEBUG = true;

    private final Object mLock = new Object();
    private final ActivityManagerService mAm;
    private final Context mContext;

    @GuardedBy("mLock")
    private boolean mEnabled;

    @GuardedBy("mLock")
    private String mOverrideString;

    @GuardedBy("mLock")
    private final ArrayMap<ComponentName, ComponentName> mFromTo = new ArrayMap<>();

    private static final String ALIAS_FILTER_ACTION = "android.intent.action.EXPERIMENTAL_IS_ALIAS";
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
     * (Re-)loads aliases from <meta-data> and the device config override.
     */
    public void update(boolean enabled, String overrides) {
        synchronized (mLock) {
            if (enabled == mEnabled && Objects.equals(overrides, mOverrideString)) {
                return;
            }
            if (enabled != mEnabled) {
                Slog.i(TAG, (enabled ? "Enabling" : "Disabling") + " component aliases...");
                if (enabled) {
                    mPackageMonitor.register(mAm.mContext, UserHandle.ALL,
                            /* externalStorage= */ false, BackgroundThread.getHandler());
                } else {
                    mPackageMonitor.unregister();
                }
            }
            mEnabled = enabled;
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
            refreshLocked();
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
        Intent i = new Intent(ALIAS_FILTER_ACTION);

        final List<ResolveInfo> services = mContext.getPackageManager().queryIntentServicesAsUser(
                i, PACKAGE_QUERY_FLAGS, UserHandle.USER_SYSTEM);

        extractAliases(services);

        if (DEBUG) Slog.d(TAG, "Scanning receiver aliases...");
        final List<ResolveInfo> receivers = mContext.getPackageManager()
                .queryBroadcastReceiversAsUser(i, PACKAGE_QUERY_FLAGS, UserHandle.USER_SYSTEM);

        extractAliases(receivers);

        // TODO: Scan for other component types as well.
    }

    private void extractAliases(List<ResolveInfo> components) {
        for (ResolveInfo ri : components) {
            final ComponentInfo ci = ri.getComponentInfo();
            final ComponentName from = ci.getComponentName();
            final ComponentName to = unflatten(ci.metaData.getString(META_DATA_ALIAS_TARGET));
            if (to == null) {
                continue;
            }
            if (DEBUG) {
                Slog.d(TAG, "" + from.flattenToShortString() + " -> " + to.flattenToShortString());
            }
            mFromTo.put(from, to);
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

                if (DEBUG) {
                    Slog.d(TAG,
                            "" + from.flattenToShortString() + " -> " + to.flattenToShortString());
                }
                mFromTo.put(from, to);
            }
        }
    }

    private ComponentName unflatten(String name) {
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
            int packageFlags, int userId, int callingUid) {
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

        List<ResolveInfo> resolved = pmi.queryIntentReceivers(i,
                resolvedType, packageFlags, callingUid, userId);
        if (resolved == null || resolved.size() == 0) {
            // Target component not found.
            Slog.w(TAG, "Alias target " + target.flattenToShortString() + " not found");
            return null;
        }
        return new Resolution<>(receiver, resolved.get(0));
    }
}
