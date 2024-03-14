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

package com.android.server.companion.presence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.PerUser;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.association.AssociationStore;
import com.android.server.companion.utils.PackageUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages communication with companion applications via
 * {@link android.companion.ICompanionDeviceService} interface, including "connecting" (binding) to
 * the services, maintaining the connection (the binding), and invoking callback methods such as
 * {@link CompanionDeviceService#onDeviceAppeared(AssociationInfo)},
 * {@link CompanionDeviceService#onDeviceDisappeared(AssociationInfo)} and
 * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} in the
 * application process.
 *
 * <p>
 * The following is the list of the APIs provided by {@link CompanionAppBinder} (to be
 * utilized by {@link CompanionDeviceManagerService}):
 * <ul>
 * <li> {@link #bindCompanionApplication(int, String, boolean, CompanionServiceConnector.Listener)}
 * <li> {@link #unbindCompanionApplication(int, String)}
 * <li> {@link #isCompanionApplicationBound(int, String)}
 * <li> {@link #isRebindingCompanionApplicationScheduled(int, String)}
 * </ul>
 *
 * @see CompanionDeviceService
 * @see android.companion.ICompanionDeviceService
 * @see CompanionServiceConnector
 */
@SuppressLint("LongLogTag")
public class CompanionAppBinder {
    private static final String TAG = "CDM_CompanionAppBinder";

    private static final long REBIND_TIMEOUT = 10 * 1000; // 10 sec

    @NonNull
    private final Context mContext;
    @NonNull
    private final AssociationStore mAssociationStore;
    @NonNull
    private final ObservableUuidStore mObservableUuidStore;
    @NonNull
    private final CompanionServicesRegister mCompanionServicesRegister;

    private final PowerManagerInternal mPowerManagerInternal;

    @NonNull
    @GuardedBy("mBoundCompanionApplications")
    private final AndroidPackageMap<List<CompanionServiceConnector>>
            mBoundCompanionApplications;
    @NonNull
    @GuardedBy("mScheduledForRebindingCompanionApplications")
    private final AndroidPackageMap<Boolean> mScheduledForRebindingCompanionApplications;

    public CompanionAppBinder(@NonNull Context context,
            @NonNull AssociationStore associationStore,
            @NonNull ObservableUuidStore observableUuidStore,
            @NonNull PowerManagerInternal powerManagerInternal) {
        mContext = context;
        mAssociationStore = associationStore;
        mObservableUuidStore = observableUuidStore;
        mPowerManagerInternal = powerManagerInternal;
        mCompanionServicesRegister = new CompanionServicesRegister();
        mBoundCompanionApplications = new AndroidPackageMap<>();
        mScheduledForRebindingCompanionApplications = new AndroidPackageMap<>();
    }

    /**
     * On package changed.
     */
    public void onPackagesChanged(@UserIdInt int userId) {
        mCompanionServicesRegister.invalidate(userId);
    }

    /**
     * CDM binds to the companion app.
     */
    public void bindCompanionApplication(@UserIdInt int userId, @NonNull String packageName,
            boolean isSelfManaged, CompanionServiceConnector.Listener listener) {
        Slog.i(TAG, "Binding user=[" + userId + "], package=[" + packageName + "], isSelfManaged=["
                + isSelfManaged + "]...");

        final List<ComponentName> companionServices =
                mCompanionServicesRegister.forPackage(userId, packageName);
        if (companionServices.isEmpty()) {
            Slog.e(TAG, "Can not bind companion applications u" + userId + "/" + packageName + ": "
                    + "eligible CompanionDeviceService not found.\n"
                    + "A CompanionDeviceService should declare an intent-filter for "
                    + "\"android.companion.CompanionDeviceService\" action and require "
                    + "\"android.permission.BIND_COMPANION_DEVICE_SERVICE\" permission.");
            return;
        }

        final List<CompanionServiceConnector> serviceConnectors = new ArrayList<>();
        synchronized (mBoundCompanionApplications) {
            if (mBoundCompanionApplications.containsValueForPackage(userId, packageName)) {
                Slog.w(TAG, "The package is ALREADY bound.");
                return;
            }

            for (int i = 0; i < companionServices.size(); i++) {
                boolean isPrimary = i == 0;
                serviceConnectors.add(CompanionServiceConnector.newInstance(mContext, userId,
                        companionServices.get(i), isSelfManaged, isPrimary));
            }

            mBoundCompanionApplications.setValueForPackage(userId, packageName, serviceConnectors);
        }

        // Set listeners for both Primary and Secondary connectors.
        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.setListener(listener);
        }

        // Now "bind" all the connectors: the primary one and the rest of them.
        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.connect();
        }
    }

    /**
     * CDM unbinds the companion app.
     */
    public void unbindCompanionApplication(@UserIdInt int userId, @NonNull String packageName) {
        Slog.i(TAG, "Unbinding user=[" + userId + "], package=[" + packageName + "]...");

        final List<CompanionServiceConnector> serviceConnectors;

        synchronized (mBoundCompanionApplications) {
            serviceConnectors = mBoundCompanionApplications.removePackage(userId, packageName);
        }

        synchronized (mScheduledForRebindingCompanionApplications) {
            mScheduledForRebindingCompanionApplications.removePackage(userId, packageName);
        }

        if (serviceConnectors == null) {
            Slog.e(TAG, "The package is not bound.");
            return;
        }

        for (CompanionServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.postUnbind();
        }
    }

    /**
     * @return whether the companion application is bound now.
     */
    public boolean isCompanionApplicationBound(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mBoundCompanionApplications) {
            return mBoundCompanionApplications.containsValueForPackage(userId, packageName);
        }
    }

    /**
     * Remove bound apps for package.
     */
    public void removePackage(int userId, String packageName) {
        synchronized (mBoundCompanionApplications) {
            mBoundCompanionApplications.removePackage(userId, packageName);
        }
    }

    /**
     * Schedule rebinding for the package.
     */
    public void scheduleRebinding(@UserIdInt int userId, @NonNull String packageName,
            CompanionServiceConnector serviceConnector) {
        Slog.i(TAG, "scheduleRebinding() " + userId + "/" + packageName);

        if (isRebindingCompanionApplicationScheduled(userId, packageName)) {
            Slog.i(TAG, "CompanionApplication rebinding has been scheduled, skipping "
                        + serviceConnector.getComponentName());
            return;
        }

        if (serviceConnector.isPrimary()) {
            synchronized (mScheduledForRebindingCompanionApplications) {
                mScheduledForRebindingCompanionApplications.setValueForPackage(
                        userId, packageName, true);
            }
        }

        // Rebinding in 10 seconds.
        Handler.getMain().postDelayed(() ->
                        onRebindingCompanionApplicationTimeout(userId, packageName,
                                serviceConnector),
                REBIND_TIMEOUT);
    }

    private boolean isRebindingCompanionApplicationScheduled(
            @UserIdInt int userId, @NonNull String packageName) {
        synchronized (mScheduledForRebindingCompanionApplications) {
            return mScheduledForRebindingCompanionApplications.containsValueForPackage(
                    userId, packageName);
        }
    }

    private void onRebindingCompanionApplicationTimeout(
            @UserIdInt int userId, @NonNull String packageName,
            @NonNull CompanionServiceConnector serviceConnector) {
        // Re-mark the application is bound.
        if (serviceConnector.isPrimary()) {
            synchronized (mBoundCompanionApplications) {
                if (!mBoundCompanionApplications.containsValueForPackage(userId, packageName)) {
                    List<CompanionServiceConnector> serviceConnectors =
                            Collections.singletonList(serviceConnector);
                    mBoundCompanionApplications.setValueForPackage(userId, packageName,
                            serviceConnectors);
                }
            }

            synchronized (mScheduledForRebindingCompanionApplications) {
                mScheduledForRebindingCompanionApplications.removePackage(userId, packageName);
            }
        }

        serviceConnector.connect();
    }

    /**
     * Dump bound apps.
     */
    public void dump(@NonNull PrintWriter out) {
        out.append("Companion Device Application Controller: \n");

        synchronized (mBoundCompanionApplications) {
            out.append("  Bound Companion Applications: ");
            if (mBoundCompanionApplications.size() == 0) {
                out.append("<empty>\n");
            } else {
                out.append("\n");
                mBoundCompanionApplications.dump(out);
            }
        }

        out.append("  Companion Applications Scheduled For Rebinding: ");
        synchronized (mScheduledForRebindingCompanionApplications) {
            if (mScheduledForRebindingCompanionApplications.size() == 0) {
                out.append("<empty>\n");
            } else {
                out.append("\n");
                mScheduledForRebindingCompanionApplications.dump(out);
            }
        }
    }

    @Nullable
    CompanionServiceConnector getPrimaryServiceConnector(
            @UserIdInt int userId, @NonNull String packageName) {
        final List<CompanionServiceConnector> connectors;
        synchronized (mBoundCompanionApplications) {
            connectors = mBoundCompanionApplications.getValueForPackage(userId, packageName);
        }
        return connectors != null ? connectors.get(0) : null;
    }

    private class CompanionServicesRegister extends PerUser<Map<String, List<ComponentName>>> {
        @Override
        public synchronized @NonNull Map<String, List<ComponentName>> forUser(
                @UserIdInt int userId) {
            return super.forUser(userId);
        }

        synchronized @NonNull List<ComponentName> forPackage(
                @UserIdInt int userId, @NonNull String packageName) {
            return forUser(userId).getOrDefault(packageName, Collections.emptyList());
        }

        synchronized void invalidate(@UserIdInt int userId) {
            remove(userId);
        }

        @Override
        protected final @NonNull Map<String, List<ComponentName>> create(@UserIdInt int userId) {
            return PackageUtils.getCompanionServicesForUser(mContext, userId);
        }
    }

    /**
     * Associates an Android package (defined by userId + packageName) with a value of type T.
     */
    private static class AndroidPackageMap<T> extends SparseArray<Map<String, T>> {

        void setValueForPackage(
                @UserIdInt int userId, @NonNull String packageName, @NonNull T value) {
            Map<String, T> forUser = get(userId);
            if (forUser == null) {
                forUser = /* Map<String, T> */ new HashMap();
                put(userId, forUser);
            }

            forUser.put(packageName, value);
        }

        boolean containsValueForPackage(@UserIdInt int userId, @NonNull String packageName) {
            final Map<String, ?> forUser = get(userId);
            return forUser != null && forUser.containsKey(packageName);
        }

        T getValueForPackage(@UserIdInt int userId, @NonNull String packageName) {
            final Map<String, T> forUser = get(userId);
            return forUser != null ? forUser.get(packageName) : null;
        }

        T removePackage(@UserIdInt int userId, @NonNull String packageName) {
            final Map<String, T> forUser = get(userId);
            if (forUser == null) return null;
            return forUser.remove(packageName);
        }

        void dump() {
            if (size() == 0) {
                Log.d(TAG, "<empty>");
                return;
            }

            for (int i = 0; i < size(); i++) {
                final int userId = keyAt(i);
                final Map<String, T> forUser = get(userId);
                if (forUser.isEmpty()) {
                    Log.d(TAG, "u" + userId + ": <empty>");
                }

                for (Map.Entry<String, T> packageValue : forUser.entrySet()) {
                    final String packageName = packageValue.getKey();
                    final T value = packageValue.getValue();
                    Log.d(TAG, "u" + userId + "\\" + packageName + " -> " + value);
                }
            }
        }

        private void dump(@NonNull PrintWriter out) {
            for (int i = 0; i < size(); i++) {
                final int userId = keyAt(i);
                final Map<String, T> forUser = get(userId);
                if (forUser.isEmpty()) {
                    out.append("    u").append(String.valueOf(userId)).append(": <empty>\n");
                }

                for (Map.Entry<String, T> packageValue : forUser.entrySet()) {
                    final String packageName = packageValue.getKey();
                    final T value = packageValue.getValue();
                    out.append("    u").append(String.valueOf(userId)).append("\\")
                            .append(packageName).append(" -> ")
                            .append(value.toString()).append('\n');
                }
            }
        }
    }
}
