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

package com.android.server.companion;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.PerUser;
import com.android.internal.util.CollectionUtils;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages communication with companion applications via
 * {@link android.companion.ICompanionDeviceService} interface, including "connecting" (binding) to
 * the services, maintaining the connection (the binding), and invoking callback methods such as
 * {@link CompanionDeviceService#onDeviceAppeared(AssociationInfo)} and
 * {@link CompanionDeviceService#onDeviceDisappeared(AssociationInfo)} in the application process.
 *
 * <p>
 * The following is the list of the APIs provided by {@link CompanionApplicationController} (to be
 * utilized by {@link CompanionDeviceManagerService}):
 * <ul>
 * <li> {@link #bindCompanionApplication(int, String, boolean)}
 * <li> {@link #unbindCompanionApplication(int, String)}
 * <li> {@link #notifyCompanionApplicationDeviceAppeared(AssociationInfo)}
 * <li> {@link #notifyCompanionApplicationDeviceDisappeared(AssociationInfo)}
 * <li> {@link #isCompanionApplicationBound(int, String)}
 * <li> {@link #isRebindingCompanionApplicationScheduled(int, String)}
 * </ul>
 *
 * @see CompanionDeviceService
 * @see android.companion.ICompanionDeviceService
 * @see CompanionDeviceServiceConnector
 */
@SuppressLint("LongLogTag")
class CompanionApplicationController {
    static final boolean DEBUG = false;
    private static final String TAG = "CompanionDevice_ApplicationController";

    private static final long REBIND_TIMEOUT = 10 * 1000; // 10 sec

    interface Callback {
        /**
         * @return {@code true} if should schedule rebinding.
         *         {@code false} if we do not need to rebind.
         */
        boolean onCompanionApplicationBindingDied(
                @UserIdInt int userId, @NonNull String packageName);

        /**
         * Callback after timeout for previously scheduled rebind has passed.
         */
        void onRebindCompanionApplicationTimeout(
                @UserIdInt int userId, @NonNull String packageName);
    }

    private final @NonNull Context mContext;
    private final @NonNull Callback mCallback;
    private final @NonNull CompanionServicesRegister mCompanionServicesRegister;
    @GuardedBy("mBoundCompanionApplications")
    private final @NonNull AndroidPackageMap<List<CompanionDeviceServiceConnector>>
            mBoundCompanionApplications;
    private final @NonNull AndroidPackageMap<Boolean> mScheduledForRebindingCompanionApplications;

    CompanionApplicationController(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
        mCompanionServicesRegister = new CompanionServicesRegister();
        mBoundCompanionApplications = new AndroidPackageMap<>();
        mScheduledForRebindingCompanionApplications = new AndroidPackageMap<>();
    }

    void onPackagesChanged(@UserIdInt int userId) {
        mCompanionServicesRegister.invalidate(userId);
    }

    void bindCompanionApplication(@UserIdInt int userId, @NonNull String packageName,
            boolean isSelfManaged) {
        if (DEBUG) {
            Log.i(TAG, "bind() u" + userId + "/" + packageName
                    + " isSelfManaged=" + isSelfManaged);
        }

        final List<ComponentName> companionServices =
                mCompanionServicesRegister.forPackage(userId, packageName);
        if (companionServices.isEmpty()) {
            Slog.w(TAG, "Can not bind companion applications u" + userId + "/" + packageName + ": "
                    + "eligible CompanionDeviceService not found.\n"
                    + "A CompanionDeviceService should declare an intent-filter for "
                    + "\"android.companion.CompanionDeviceService\" action and require "
                    + "\"android.permission.BIND_COMPANION_DEVICE_SERVICE\" permission.");
            return;
        }

        final List<CompanionDeviceServiceConnector> serviceConnectors;
        synchronized (mBoundCompanionApplications) {
            if (mBoundCompanionApplications.containsValueForPackage(userId, packageName)) {
                if (DEBUG) Log.e(TAG, "u" + userId + "/" + packageName + " is ALREADY bound.");
                return;
            }

            serviceConnectors = CollectionUtils.map(companionServices, componentName ->
                            CompanionDeviceServiceConnector.newInstance(mContext, userId,
                                    componentName, isSelfManaged));
            mBoundCompanionApplications.setValueForPackage(userId, packageName, serviceConnectors);
        }

        // The first connector in the list is always the primary connector: set a listener to it.
        serviceConnectors.get(0).setListener(this::onPrimaryServiceBindingDied);

        // Now "bind" all the connectors: the primary one and the rest of them.
        for (CompanionDeviceServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.connect();
        }
    }

    void unbindCompanionApplication(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Log.i(TAG, "unbind() u" + userId + "/" + packageName);

        final List<CompanionDeviceServiceConnector> serviceConnectors;
        synchronized (mBoundCompanionApplications) {
            serviceConnectors = mBoundCompanionApplications.removePackage(userId, packageName);
        }
        if (serviceConnectors == null) {
            if (DEBUG) {
                Log.e(TAG, "unbindCompanionApplication(): "
                        + "u" + userId + "/" + packageName + " is NOT bound");
                Log.d(TAG, "Stacktrace", new Throwable());
            }
            return;
        }

        for (CompanionDeviceServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.postUnbind();
        }
    }

    boolean isCompanionApplicationBound(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mBoundCompanionApplications) {
            return mBoundCompanionApplications.containsValueForPackage(userId, packageName);
        }
    }

    private void scheduleRebinding(@UserIdInt int userId, @NonNull String packageName) {
        mScheduledForRebindingCompanionApplications.setValueForPackage(userId, packageName, true);

        Handler.getMain().postDelayed(() ->
                onRebindingCompanionApplicationTimeout(userId, packageName), REBIND_TIMEOUT);
    }

    boolean isRebindingCompanionApplicationScheduled(
            @UserIdInt int userId, @NonNull String packageName) {
        return mScheduledForRebindingCompanionApplications
                .containsValueForPackage(userId, packageName);
    }

    private void onRebindingCompanionApplicationTimeout(
            @UserIdInt int userId, @NonNull String packageName) {
        mScheduledForRebindingCompanionApplications.removePackage(userId, packageName);

        mCallback.onRebindCompanionApplicationTimeout(userId, packageName);
    }

    void notifyCompanionApplicationDeviceAppeared(AssociationInfo association) {
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        if (DEBUG) {
            Log.i(TAG, "notifyDevice_Appeared() id=" + association.getId() + " u" + userId
                    + "/" + packageName);
        }

        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        if (primaryServiceConnector == null) {
            if (DEBUG) {
                Log.e(TAG, "notify_CompanionApplicationDevice_Appeared(): "
                        + "u" + userId + "/" + packageName + " is NOT bound.");
                Log.d(TAG, "Stacktrace", new Throwable());
            }
            return;
        }

        primaryServiceConnector.postOnDeviceAppeared(association);
    }

    void notifyCompanionApplicationDeviceDisappeared(AssociationInfo association) {
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        if (DEBUG) {
            Log.i(TAG, "notifyDevice_Disappeared() id=" + association.getId() + " u" + userId
                    + "/" + packageName);
        }

        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        if (primaryServiceConnector == null) {
            if (DEBUG) {
                Log.e(TAG, "notify_CompanionApplicationDevice_Disappeared(): "
                        + "u" + userId + "/" + packageName + " is NOT bound.");
                Log.d(TAG, "Stacktrace", new Throwable());
            }
            return;
        }

        primaryServiceConnector.postOnDeviceDisappeared(association);
    }

    void dump(@NonNull PrintWriter out) {
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
        if (mScheduledForRebindingCompanionApplications.size() == 0) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            mScheduledForRebindingCompanionApplications.dump(out);
        }
    }

    private void onPrimaryServiceBindingDied(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Log.i(TAG, "onPrimaryServiceBindingDied() u" + userId + "/" + packageName);

        // First: mark as NOT bound.
        synchronized (mBoundCompanionApplications) {
            mBoundCompanionApplications.removePackage(userId, packageName);
        }

        // Second: invoke callback, schedule rebinding if needed.
        final boolean shouldScheduleRebind =
                mCallback.onCompanionApplicationBindingDied(userId, packageName);
        if (shouldScheduleRebind) {
            scheduleRebinding(userId, packageName);
        }
    }

    private @Nullable CompanionDeviceServiceConnector getPrimaryServiceConnector(
            @UserIdInt int userId, @NonNull String packageName) {
        final List<CompanionDeviceServiceConnector> connectors;
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
