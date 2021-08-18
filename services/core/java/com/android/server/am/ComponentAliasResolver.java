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
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

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
        if (DEBUG) Slog.d(TAG, "Scanning aliases...");
        Intent i = new Intent(ALIAS_FILTER_ACTION);

        List<ResolveInfo> services = mContext.getPackageManager().queryIntentServicesAsUser(
                i,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                        | PackageManager.MATCH_ANY_USER
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.GET_META_DATA,
                UserHandle.USER_SYSTEM);

        for (ResolveInfo ri : services) {
            final ComponentInfo ci = ri.getComponentInfo();
            final ComponentName from = ci.getComponentName();
            final ComponentName to = ComponentName.unflattenFromString(
                    ci.metaData.getString(META_DATA_ALIAS_TARGET));
            if (!validateComponentName(to)) {
                continue;
            }
            if (DEBUG) {
                Slog.d(TAG, "" + from.flattenToShortString() + " -> " + to.flattenToShortString());
            }
            mFromTo.put(from, to);
        }

        // TODO: Scan for other component types as well.
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
        for (String line : mOverrideString.split("\\+")) {
            final String[] fields = line.split("\\:+", 2);
            final ComponentName from = ComponentName.unflattenFromString(fields[0]);
            if (!validateComponentName(from)) {
                continue;
            }

            if (fields.length == 1) {
                if (DEBUG) Slog.d(TAG, "" + from.flattenToShortString() + " [removed]");
                mFromTo.remove(from);
            } else {
                final ComponentName to = ComponentName.unflattenFromString(fields[1]);
                if (!validateComponentName(to)) {
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

    private boolean validateComponentName(ComponentName cn) {
        if (cn != null) {
            return true;
        }
        Slog.e(TAG, "Invalid component name detected: " + cn);
        return false;
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
    public static class Resolution {
        @NonNull
        public final Intent sourceIntent;

        /** "From" component. Null if component alias is disabled. */
        @Nullable
        public final ComponentName sourceComponent;

        /** "To" component. Null if component alias is disabled, or the source isn't an alias. */
        @Nullable
        public final ComponentName resolvedComponent;

        public Resolution(Intent sourceIntent,
                ComponentName sourceComponent, ComponentName resolvedComponent) {
            this.sourceIntent = sourceIntent;
            this.sourceComponent = sourceComponent;
            this.resolvedComponent = resolvedComponent;
        }

        @Nullable
        public boolean isAlias() {
            return this.resolvedComponent != null;
        }

        @Nullable
        public ComponentName getAliasComponent() {
            return isAlias() ? sourceComponent : null;
        }

        @Nullable
        public ComponentName getTargetComponent() {
            return isAlias() ? resolvedComponent : null;
        }
    }

    @Nullable
    public Resolution resolveService(
            @NonNull Intent service, @Nullable String resolvedType,
            int packageFlags, int userId, int callingUid) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!mEnabled) {
                    return new Resolution(service, null, null);
                }

                PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);

                ResolveInfo rInfo = pmi.resolveService(service,
                        resolvedType, packageFlags, userId, callingUid);
                ServiceInfo sInfo = rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    return null; // Service not found.
                }
                final ComponentName alias =
                        new ComponentName(sInfo.applicationInfo.packageName, sInfo.name);
                final ComponentName target = mFromTo.get(alias);

                if (target != null) {
                    // It's an alias. Keep the original intent, and rewrite it.
                    service.setOriginalIntent(new Intent(service));

                    service.setPackage(null);
                    service.setComponent(target);

                    if (DEBUG) {
                        Slog.d(TAG, "Alias resolved: " + alias.flattenToShortString()
                                + " -> " + target.flattenToShortString());
                    }
                }
                return new Resolution(service, alias, target);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
