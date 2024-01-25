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
import android.companion.DevicePresenceEvent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.PerUser;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;

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
 * The following is the list of the APIs provided by {@link CompanionApplicationController} (to be
 * utilized by {@link CompanionDeviceManagerService}):
 * <ul>
 * <li> {@link #bindCompanionApplication(int, String, boolean)}
 * <li> {@link #unbindCompanionApplication(int, String)}
 * <li> {@link #notifyCompanionApplicationDevicePresenceEvent(AssociationInfo, int)}
 * <li> {@link #isCompanionApplicationBound(int, String)}
 * <li> {@link #isRebindingCompanionApplicationScheduled(int, String)}
 * </ul>
 *
 * @see CompanionDeviceService
 * @see android.companion.ICompanionDeviceService
 * @see CompanionDeviceServiceConnector
 */
@SuppressLint("LongLogTag")
public class CompanionApplicationController {
    static final boolean DEBUG = false;
    private static final String TAG = "CDM_CompanionApplicationController";

    private static final long REBIND_TIMEOUT = 10 * 1000; // 10 sec

    private final @NonNull Context mContext;
    private final @NonNull AssociationStore mAssociationStore;
    private final @NonNull ObservableUuidStore mObservableUuidStore;
    private final @NonNull CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private final @NonNull CompanionServicesRegister mCompanionServicesRegister;

    @GuardedBy("mBoundCompanionApplications")
    private final @NonNull AndroidPackageMap<List<CompanionDeviceServiceConnector>>
            mBoundCompanionApplications;
    @GuardedBy("mScheduledForRebindingCompanionApplications")
    private final @NonNull AndroidPackageMap<Boolean> mScheduledForRebindingCompanionApplications;

    CompanionApplicationController(Context context, AssociationStore associationStore,
            ObservableUuidStore observableUuidStore,
            CompanionDevicePresenceMonitor companionDevicePresenceMonitor) {
        mContext = context;
        mAssociationStore = associationStore;
        mObservableUuidStore =  observableUuidStore;
        mDevicePresenceMonitor = companionDevicePresenceMonitor;
        mCompanionServicesRegister = new CompanionServicesRegister();
        mBoundCompanionApplications = new AndroidPackageMap<>();
        mScheduledForRebindingCompanionApplications = new AndroidPackageMap<>();
    }

    void onPackagesChanged(@UserIdInt int userId) {
        mCompanionServicesRegister.invalidate(userId);
    }

    /**
     * CDM binds to the companion app.
     */
    public void bindCompanionApplication(@UserIdInt int userId, @NonNull String packageName,
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

        final List<CompanionDeviceServiceConnector> serviceConnectors = new ArrayList<>();
        synchronized (mBoundCompanionApplications) {
            if (mBoundCompanionApplications.containsValueForPackage(userId, packageName)) {
                if (DEBUG) Log.e(TAG, "u" + userId + "/" + packageName + " is ALREADY bound.");
                return;
            }

            for (int i = 0; i < companionServices.size(); i++) {
                boolean isPrimary = i == 0;
                serviceConnectors.add(CompanionDeviceServiceConnector.newInstance(mContext, userId,
                        companionServices.get(i), isSelfManaged, isPrimary));
            }

            mBoundCompanionApplications.setValueForPackage(userId, packageName, serviceConnectors);
        }

        // Set listeners for both Primary and Secondary connectors.
        for (CompanionDeviceServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.setListener(this::onBinderDied);
        }

        // Now "bind" all the connectors: the primary one and the rest of them.
        for (CompanionDeviceServiceConnector serviceConnector : serviceConnectors) {
            serviceConnector.connect();
        }
    }

    /**
     * CDM unbinds the companion app.
     */
    public void unbindCompanionApplication(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Log.i(TAG, "unbind() u" + userId + "/" + packageName);

        final List<CompanionDeviceServiceConnector> serviceConnectors;

        synchronized (mBoundCompanionApplications) {
            serviceConnectors = mBoundCompanionApplications.removePackage(userId, packageName);
        }

        synchronized (mScheduledForRebindingCompanionApplications) {
            mScheduledForRebindingCompanionApplications.removePackage(userId, packageName);
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

    /**
     * @return whether the companion application is bound now.
     */
    public boolean isCompanionApplicationBound(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mBoundCompanionApplications) {
            return mBoundCompanionApplications.containsValueForPackage(userId, packageName);
        }
    }

    private void scheduleRebinding(@UserIdInt int userId, @NonNull String packageName,
            CompanionDeviceServiceConnector serviceConnector) {
        Slog.i(TAG, "scheduleRebinding() " + userId + "/" + packageName);

        if (isRebindingCompanionApplicationScheduled(userId, packageName)) {
            if (DEBUG) {
                Log.i(TAG, "CompanionApplication rebinding has been scheduled, skipping "
                        + serviceConnector.getComponentName());
            }
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
                onRebindingCompanionApplicationTimeout(userId, packageName, serviceConnector),
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
            @NonNull CompanionDeviceServiceConnector serviceConnector) {
        // Re-mark the application is bound.
        if (serviceConnector.isPrimary()) {
            synchronized (mBoundCompanionApplications) {
                if (!mBoundCompanionApplications.containsValueForPackage(userId, packageName)) {
                    List<CompanionDeviceServiceConnector> serviceConnectors =
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

    void notifyCompanionApplicationDeviceAppeared(AssociationInfo association) {
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        Slog.i(TAG, "notifyDevice_Appeared() id=" + association.getId() + " u" + userId
                    + "/" + packageName);

        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        if (primaryServiceConnector == null) {
            Slog.e(TAG, "notify_CompanionApplicationDevice_Appeared(): "
                        + "u" + userId + "/" + packageName + " is NOT bound.");
            Slog.e(TAG, "Stacktrace", new Throwable());
            return;
        }

        Log.i(TAG, "Calling onDeviceAppeared to userId=[" + userId + "] package=["
                + packageName + "] associationId=[" + association.getId() + "]");

        primaryServiceConnector.postOnDeviceAppeared(association);
    }

    void notifyCompanionApplicationDeviceDisappeared(AssociationInfo association) {
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        Slog.i(TAG, "notifyDevice_Disappeared() id=" + association.getId() + " u" + userId
                + "/" + packageName);

        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        if (primaryServiceConnector == null) {
            Slog.e(TAG, "notify_CompanionApplicationDevice_Disappeared(): "
                        + "u" + userId + "/" + packageName + " is NOT bound.");
            Slog.e(TAG, "Stacktrace", new Throwable());
            return;
        }

        Log.i(TAG, "Calling onDeviceDisappeared to userId=[" + userId + "] package=["
                + packageName + "] associationId=[" + association.getId() + "]");

        primaryServiceConnector.postOnDeviceDisappeared(association);
    }

    void notifyCompanionApplicationDevicePresenceEvent(AssociationInfo association, int event) {
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        final DevicePresenceEvent devicePresenceEvent =
                new DevicePresenceEvent(association.getId(), event, null);

        if (primaryServiceConnector == null) {
            Slog.e(TAG, "notifyCompanionApplicationDevicePresenceEvent(): "
                        + "u" + userId + "/" + packageName
                        + " event=[ " + event  + " ] is NOT bound.");
            Slog.e(TAG, "Stacktrace", new Throwable());
            return;
        }

        Slog.i(TAG, "Calling onDevicePresenceEvent() to userId=[" + userId + "] package=["
                + packageName + "] associationId=[" + association.getId()
                + "] event=[" + event + "]");

        primaryServiceConnector.postOnDevicePresenceEvent(devicePresenceEvent);
    }

    void notifyApplicationDevicePresenceEvent(ObservableUuid uuid, int event) {
        final int userId = uuid.getUserId();
        final ParcelUuid parcelUuid = uuid.getUuid();
        final String packageName = uuid.getPackageName();
        final CompanionDeviceServiceConnector primaryServiceConnector =
                getPrimaryServiceConnector(userId, packageName);
        final DevicePresenceEvent devicePresenceEvent =
                new DevicePresenceEvent(DevicePresenceEvent.NO_ASSOCIATION, event, parcelUuid);

        if (primaryServiceConnector == null) {
            Slog.e(TAG, "notifyApplicationDevicePresenceChanged(): "
                    + "u" + userId + "/" + packageName
                    + " event=[ " + event  + " ] is NOT bound.");
            Slog.e(TAG, "Stacktrace", new Throwable());
            return;
        }

        Slog.i(TAG, "Calling onDevicePresenceEvent() to userId=[" + userId + "] package=["
                + packageName + "]" + "event= [" + event + "]");

        primaryServiceConnector.postOnDevicePresenceEvent(devicePresenceEvent);
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

    /**
     * Rebinding for Self-Managed secondary services OR Non-Self-Managed services.
     */
    private void onBinderDied(@UserIdInt int userId, @NonNull String packageName,
            @NonNull CompanionDeviceServiceConnector serviceConnector) {

        boolean isPrimary = serviceConnector.isPrimary();
        Slog.i(TAG, "onBinderDied() u" + userId + "/" + packageName + " isPrimary: " + isPrimary);

        // First: Only mark not BOUND for primary service.
        synchronized (mBoundCompanionApplications) {
            if (serviceConnector.isPrimary()) {
                mBoundCompanionApplications.removePackage(userId, packageName);
            }
        }

        // Second: schedule rebinding if needed.
        final boolean shouldScheduleRebind = shouldScheduleRebind(userId, packageName, isPrimary);

        if (shouldScheduleRebind) {
            scheduleRebinding(userId, packageName, serviceConnector);
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

    private boolean shouldScheduleRebind(int userId, String packageName, boolean isPrimary) {
        // Make sure do not schedule rebind for the case ServiceConnector still gets callback after
        // app is uninstalled.
        boolean stillAssociated = false;
        // Make sure to clean up the state for all the associations
        // that associate with this package.
        boolean shouldScheduleRebind = false;
        boolean shouldScheduleRebindForUuid = false;
        final List<ObservableUuid> uuids =
                mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);

        for (AssociationInfo ai :
                mAssociationStore.getAssociationsForPackage(userId, packageName)) {
            final int associationId = ai.getId();
            stillAssociated = true;
            if (ai.isSelfManaged()) {
                // Do not rebind if primary one is died for selfManaged application.
                if (isPrimary
                        && mDevicePresenceMonitor.isDevicePresent(associationId)) {
                    mDevicePresenceMonitor.onSelfManagedDeviceReporterBinderDied(associationId);
                    shouldScheduleRebind = false;
                }
                // Do not rebind if both primary and secondary services are died for
                // selfManaged application.
                shouldScheduleRebind = isCompanionApplicationBound(userId, packageName);
            } else if (ai.isNotifyOnDeviceNearby()) {
                // Always rebind for non-selfManaged devices.
                shouldScheduleRebind = true;
            }
        }

        for (ObservableUuid uuid : uuids) {
            if (mDevicePresenceMonitor.isDeviceUuidPresent(uuid.getUuid())) {
                shouldScheduleRebindForUuid = true;
                break;
            }
        }

        return (stillAssociated && shouldScheduleRebind) || shouldScheduleRebindForUuid;
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
