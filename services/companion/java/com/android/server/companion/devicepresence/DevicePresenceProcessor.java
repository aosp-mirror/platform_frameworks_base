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

package com.android.server.companion.devicepresence;

import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.DevicePresenceEvent.EVENT_BLE_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BLE_DISAPPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BT_CONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_BT_DISCONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED;
import static android.companion.DevicePresenceEvent.NO_ASSOCIATION;
import static android.content.Context.BLUETOOTH_SERVICE;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SHELL_UID;

import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanObserveDevicePresenceByUuid;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.companion.AssociationInfo;
import android.companion.DeviceNotAssociatedException;
import android.companion.DevicePresenceEvent;
import android.companion.ObservingDevicePresenceRequest;
import android.content.Context;
import android.hardware.power.Mode;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;
import com.android.server.companion.association.AssociationStore;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Class responsible for monitoring companion devices' "presence" status (i.e.
 * connected/disconnected for Bluetooth devices; nearby or not for BLE devices).
 *
 * <p>
 * Should only be used by
 * {@link com.android.server.companion.CompanionDeviceManagerService CompanionDeviceManagerService}
 * to which it provides the following API:
 * <ul>
 * <li> {@link #onSelfManagedDeviceConnected(int)}
 * <li> {@link #onSelfManagedDeviceDisconnected(int)}
 * <li> {@link #isDevicePresent(int)}
 * </ul>
 */
@SuppressLint("LongLogTag")
public class DevicePresenceProcessor implements AssociationStore.OnChangeListener,
        BluetoothDeviceProcessor.Callback, BleDeviceProcessor.Callback {
    private static final String TAG = "CDM_DevicePresenceProcessor";

    @NonNull
    private final Context mContext;
    @NonNull
    private final CompanionAppBinder mCompanionAppBinder;
    @NonNull
    private final AssociationStore mAssociationStore;
    @NonNull
    private final ObservableUuidStore mObservableUuidStore;
    @NonNull
    private final BluetoothDeviceProcessor mBluetoothDeviceProcessor;
    @NonNull
    private final BleDeviceProcessor mBleDeviceProcessor;
    @NonNull
    private final PowerManagerInternal mPowerManagerInternal;
    @NonNull
    private final UserManager mUserManager;

    // NOTE: Same association may appear in more than one of the following sets at the same time.
    // (E.g. self-managed devices that have MAC addresses, could be reported as present by their
    // companion applications, while at the same be connected via BT, or detected nearby by BLE
    // scanner)
    @NonNull
    private final Set<Integer> mConnectedBtDevices = new HashSet<>();
    @NonNull
    private final Set<Integer> mNearbyBleDevices = new HashSet<>();
    @NonNull
    private final Set<Integer> mReportedSelfManagedDevices = new HashSet<>();
    @NonNull
    private final Set<ParcelUuid> mConnectedUuidDevices = new HashSet<>();
    @NonNull
    @GuardedBy("mBtDisconnectedDevices")
    private final Set<Integer> mBtDisconnectedDevices = new HashSet<>();

    // A map to track device presence within 10 seconds of Bluetooth disconnection.
    // The key is the association ID, and the boolean value indicates if the device
    // was detected again within that time frame.
    @GuardedBy("mBtDisconnectedDevices")
    private final @NonNull SparseBooleanArray mBtDisconnectedDevicesBlePresence =
            new SparseBooleanArray();

    // Tracking "simulated" presence. Used for debugging and testing only.
    private final @NonNull Set<Integer> mSimulated = new HashSet<>();
    private final SimulatedDevicePresenceSchedulerHelper mSchedulerHelper =
            new SimulatedDevicePresenceSchedulerHelper();

    private final BleDeviceDisappearedScheduler mBleDeviceDisappearedScheduler =
            new BleDeviceDisappearedScheduler();

    /**
     * A structure hold the DevicePresenceEvents that are pending to be reported to the companion
     * app when the user unlocks the local device per userId.
     */
    @GuardedBy("mPendingDevicePresenceEvents")
    public final SparseArray<List<DevicePresenceEvent>> mPendingDevicePresenceEvents =
            new SparseArray<>();

    public DevicePresenceProcessor(@NonNull Context context,
            @NonNull CompanionAppBinder companionAppBinder,
            @NonNull UserManager userManager,
            @NonNull AssociationStore associationStore,
            @NonNull ObservableUuidStore observableUuidStore,
            @NonNull PowerManagerInternal powerManagerInternal) {
        mContext = context;
        mCompanionAppBinder = companionAppBinder;
        mAssociationStore = associationStore;
        mObservableUuidStore = observableUuidStore;
        mUserManager = userManager;
        mBluetoothDeviceProcessor = new BluetoothDeviceProcessor(associationStore,
                mObservableUuidStore, this);
        mBleDeviceProcessor = new BleDeviceProcessor(associationStore, this);
        mPowerManagerInternal = powerManagerInternal;
    }

    /** Initialize {@link DevicePresenceProcessor} */
    public void init(Context context) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(BLUETOOTH_SERVICE);
        if (bm == null) {
            Slog.w(TAG, "BluetoothManager is not available.");
            return;
        }
        final BluetoothAdapter btAdapter = bm.getAdapter();
        if (btAdapter == null) {
            Slog.w(TAG, "BluetoothAdapter is NOT available.");
            return;
        }

        mBluetoothDeviceProcessor.init(btAdapter);
        mBleDeviceProcessor.init(context, btAdapter);

        mAssociationStore.registerLocalListener(this);
    }

    /**
     * Process device presence start request.
     */
    public void startObservingDevicePresence(ObservingDevicePresenceRequest request,
            String packageName, int userId, boolean enforcePermissions) {
        Slog.i(TAG,
                "Start observing request=[" + request + "] for userId=[" + userId + "], package=["
                        + packageName + "]...");
        final ParcelUuid requestUuid = request.getUuid();

        if (requestUuid != null) {
            if (enforcePermissions) {
                enforceCallerCanObserveDevicePresenceByUuid(mContext, packageName, userId);
            }

            // If it's already being observed, then no-op.
            if (mObservableUuidStore.isUuidBeingObserved(requestUuid, userId, packageName)) {
                Slog.i(TAG, "UUID=[" + requestUuid + "], package=[" + packageName + "], userId=["
                        + userId + "] is already being observed.");
                return;
            }

            final ObservableUuid observableUuid = new ObservableUuid(userId, requestUuid,
                    packageName, System.currentTimeMillis());
            mObservableUuidStore.writeObservableUuid(userId, observableUuid);
        } else {
            final int associationId = request.getAssociationId();
            AssociationInfo association = mAssociationStore.getAssociationWithCallerChecks(
                    associationId);

            // If it's already being observed, then no-op.
            if (association.isNotifyOnDeviceNearby()) {
                Slog.i(TAG, "Associated device id=[" + association.getId()
                        + "] is already being observed. No-op.");
                return;
            }

            association = (new AssociationInfo.Builder(association)).setNotifyOnDeviceNearby(true)
                    .build();
            mAssociationStore.updateAssociation(association);

            // Send callback immediately if the device is present.
            if (isDevicePresent(associationId)) {
                Slog.i(TAG, "Device is already present. Triggering callback.");
                if (isBlePresent(associationId)) {
                    onDevicePresenceEvent(mNearbyBleDevices, associationId, EVENT_BLE_APPEARED);
                } else if (isBtConnected(associationId)) {
                    onDevicePresenceEvent(mConnectedBtDevices, associationId, EVENT_BT_CONNECTED);
                } else if (isSimulatePresent(associationId)) {
                    onDevicePresenceEvent(mSimulated, associationId, EVENT_BLE_APPEARED);
                }
            }
        }

        Slog.i(TAG, "Registered device presence listener.");
    }

    /**
     * Process device presence stop request.
     */
    public void stopObservingDevicePresence(ObservingDevicePresenceRequest request,
            String packageName, int userId, boolean enforcePermissions) {
        Slog.i(TAG,
                "Stop observing request=[" + request + "] for userId=[" + userId + "], package=["
                        + packageName + "]...");

        final ParcelUuid requestUuid = request.getUuid();

        if (requestUuid != null) {
            if (enforcePermissions) {
                enforceCallerCanObserveDevicePresenceByUuid(mContext, packageName, userId);
            }

            if (!mObservableUuidStore.isUuidBeingObserved(requestUuid, userId, packageName)) {
                Slog.i(TAG, "UUID=[" + requestUuid + "], package=[" + packageName + "], userId=["
                        + userId + "] is already not being observed.");
                return;
            }

            mObservableUuidStore.removeObservableUuid(userId, requestUuid, packageName);
            removeCurrentConnectedUuidDevice(requestUuid);
        } else {
            final int associationId = request.getAssociationId();
            AssociationInfo association = mAssociationStore.getAssociationWithCallerChecks(
                    associationId);

            // If it's already being observed, then no-op.
            if (!association.isNotifyOnDeviceNearby()) {
                Slog.i(TAG, "Associated device id=[" + association.getId()
                        + "] is already not being observed. No-op.");
                return;
            }

            association = (new AssociationInfo.Builder(association)).setNotifyOnDeviceNearby(false)
                    .build();
            mAssociationStore.updateAssociation(association);
        }

        Slog.i(TAG, "Unregistered device presence listener.");

        // If last listener is unregistered, then unbind application.
        if (!shouldBindPackage(userId, packageName)) {
            mCompanionAppBinder.unbindCompanionApp(userId, packageName);
        }
    }

    /**
     * For legacy device presence below Android V.
     *
     * @deprecated Use {@link #startObservingDevicePresence(ObservingDevicePresenceRequest, String,
     * int, boolean)}
     */
    @Deprecated
    public void startObservingDevicePresence(int userId, String packageName, String deviceAddress)
            throws RemoteException {
        Slog.i(TAG,
                "Start observing device=[" + deviceAddress + "] for userId=[" + userId
                        + "], package=["
                        + packageName + "]...");

        enforceCallerCanManageAssociationsForPackage(mContext, userId, packageName, null);

        AssociationInfo association = mAssociationStore.getFirstAssociationByAddress(userId,
                packageName, deviceAddress);

        if (association == null) {
            throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                    + " is not associated with device " + deviceAddress
                    + " for user " + userId));
        }

        startObservingDevicePresence(
                new ObservingDevicePresenceRequest.Builder().setAssociationId(association.getId())
                        .build(), packageName, userId, /* enforcePermissions */ true);
    }

    /**
     * For legacy device presence below Android V.
     *
     * @deprecated Use {@link #stopObservingDevicePresence(ObservingDevicePresenceRequest, String,
     * int, boolean)}
     */
    @Deprecated
    public void stopObservingDevicePresence(int userId, String packageName, String deviceAddress)
            throws RemoteException {
        Slog.i(TAG,
                "Stop observing device=[" + deviceAddress + "] for userId=[" + userId
                        + "], package=["
                        + packageName + "]...");

        enforceCallerCanManageAssociationsForPackage(mContext, userId, packageName, null);

        AssociationInfo association = mAssociationStore.getFirstAssociationByAddress(userId,
                packageName, deviceAddress);

        if (association == null) {
            throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                    + " is not associated with device " + deviceAddress
                    + " for user " + userId));
        }

        stopObservingDevicePresence(
                new ObservingDevicePresenceRequest.Builder().setAssociationId(association.getId())
                        .build(), packageName, userId, /* enforcePermissions */ true);
    }

    /**
     * @return whether the package should be bound (i.e. at least one of the devices associated with
     * the package is currently present OR the UUID to be observed by this package is
     * currently present).
     */
    private boolean shouldBindPackage(@UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> packageAssociations =
                mAssociationStore.getActiveAssociationsByPackage(userId, packageName);
        final List<ObservableUuid> observableUuids =
                mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);

        for (AssociationInfo association : packageAssociations) {
            if (!association.shouldBindWhenPresent()) continue;
            if (isDevicePresent(association.getId())) return true;
        }

        for (ObservableUuid uuid : observableUuids) {
            if (isDeviceUuidPresent(uuid.getUuid())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Bind the system to the app if it's not bound.
     *
     * Set bindImportant to true when the association is self-managed to avoid the target service
     * being killed.
     */
    private void bindApplicationIfNeeded(int userId, String packageName, boolean bindImportant) {
        if (!mCompanionAppBinder.isCompanionApplicationBound(userId, packageName)) {
            mCompanionAppBinder.bindCompanionApp(
                    userId, packageName, bindImportant, this::onBinderDied);
        } else {
            Slog.i(TAG,
                    "UserId=[" + userId + "], packageName=[" + packageName + "] is already bound.");
        }
    }

    /**
     * @return current connected UUID devices.
     */
    public Set<ParcelUuid> getCurrentConnectedUuidDevices() {
        return mConnectedUuidDevices;
    }

    /**
     * Remove current connected UUID device.
     */
    public void removeCurrentConnectedUuidDevice(ParcelUuid uuid) {
        mConnectedUuidDevices.remove(uuid);
    }

    /**
     * @return whether the associated companion devices is present. I.e. device is nearby (for BLE);
     * or devices is connected (for Bluetooth); or reported (by the application) to be
     * nearby (for "self-managed" associations).
     */
    public boolean isDevicePresent(int associationId) {
        return mReportedSelfManagedDevices.contains(associationId)
                || mConnectedBtDevices.contains(associationId)
                || mNearbyBleDevices.contains(associationId)
                || mSimulated.contains(associationId);
    }

    /**
     * @return whether the current uuid to be observed is present.
     */
    public boolean isDeviceUuidPresent(ParcelUuid uuid) {
        return mConnectedUuidDevices.contains(uuid);
    }

    /**
     * @return whether the current device is BT connected and had already reported to the app.
     */

    public boolean isBtConnected(int associationId) {
        return mConnectedBtDevices.contains(associationId);
    }

    /**
     * @return whether the current device in BLE range and had already reported to the app.
     */
    public boolean isBlePresent(int associationId) {
        return mNearbyBleDevices.contains(associationId);
    }

    /**
     * @return whether the current device had been already reported by the simulator.
     */
    public boolean isSimulatePresent(int associationId) {
        return mSimulated.contains(associationId);
    }

    /**
     * Marks a "self-managed" device as connected.
     *
     * <p>
     * Must ONLY be invoked by the
     * {@link com.android.server.companion.CompanionDeviceManagerService
     * CompanionDeviceManagerService}
     * when an application invokes
     * {@link android.companion.CompanionDeviceManager#notifyDeviceAppeared(int)
     * notifyDeviceAppeared()}
     */
    public void onSelfManagedDeviceConnected(int associationId) {
        onDevicePresenceEvent(mReportedSelfManagedDevices,
                associationId, EVENT_SELF_MANAGED_APPEARED);
    }

    /**
     * Marks a "self-managed" device as disconnected.
     *
     * <p>
     * Must ONLY be invoked by the
     * {@link com.android.server.companion.CompanionDeviceManagerService
     * CompanionDeviceManagerService}
     * when an application invokes
     * {@link android.companion.CompanionDeviceManager#notifyDeviceDisappeared(int)
     * notifyDeviceDisappeared()}
     */
    public void onSelfManagedDeviceDisconnected(int associationId) {
        onDevicePresenceEvent(mReportedSelfManagedDevices,
                associationId, EVENT_SELF_MANAGED_DISAPPEARED);
    }

    /**
     * Marks a "self-managed" device as disconnected when binderDied.
     */
    public void onSelfManagedDeviceReporterBinderDied(int associationId) {
        onDevicePresenceEvent(mReportedSelfManagedDevices,
                associationId, EVENT_SELF_MANAGED_DISAPPEARED);
    }

    @Override
    public void onBluetoothCompanionDeviceConnected(int associationId, int userId) {
        Slog.i(TAG, "onBluetoothCompanionDeviceConnected: "
                + "associationId( " + associationId + " )");
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            onDeviceLocked(associationId, userId, EVENT_BT_CONNECTED, /* ParcelUuid */ null);
            return;
        }

        synchronized (mBtDisconnectedDevices) {
            // A device is considered reconnected within 10 seconds if a pending BLE lost report is
            // followed by a detected Bluetooth connection.
            boolean isReconnected = mBtDisconnectedDevices.contains(associationId);
            if (isReconnected) {
                Slog.i(TAG, "Device ( " + associationId + " ) is reconnected within 10s.");
                mBleDeviceDisappearedScheduler.unScheduleDeviceDisappeared(associationId);
            }

            Slog.i(TAG, "onBluetoothCompanionDeviceConnected: "
                    + "associationId( " + associationId + " )");
            onDevicePresenceEvent(mConnectedBtDevices, associationId, EVENT_BT_CONNECTED);

            // Stop the BLE scan if all devices report BT connected status and BLE was present.
            if (canStopBleScan()) {
                mBleDeviceProcessor.stopScanIfNeeded();
            }

        }
    }

    @Override
    public void onBluetoothCompanionDeviceDisconnected(int associationId, int userId) {
        Slog.i(TAG, "onBluetoothCompanionDeviceDisconnected "
                + "associationId( " + associationId + " )");

        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            onDeviceLocked(associationId, userId, EVENT_BT_DISCONNECTED, /* ParcelUuid */ null);
            return;
        }

        // Start BLE scanning when the device is disconnected.
        mBleDeviceProcessor.startScan();

        onDevicePresenceEvent(mConnectedBtDevices, associationId, EVENT_BT_DISCONNECTED);
        // If current device is BLE present but BT is disconnected , means it will be
        // potentially out of range later. Schedule BLE disappeared callback.
        if (isBlePresent(associationId)) {
            synchronized (mBtDisconnectedDevices) {
                mBtDisconnectedDevices.add(associationId);
            }
            mBleDeviceDisappearedScheduler.scheduleBleDeviceDisappeared(associationId);
        }
    }


    @Override
    public void onBleCompanionDeviceFound(int associationId, int userId) {
        Slog.i(TAG, "onBleCompanionDeviceFound " + "associationId( " + associationId + " )");
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            onDeviceLocked(associationId, userId, EVENT_BLE_APPEARED, /* ParcelUuid */ null);
            return;
        }

        onDevicePresenceEvent(mNearbyBleDevices, associationId, EVENT_BLE_APPEARED);
        synchronized (mBtDisconnectedDevices) {
            final boolean isCurrentPresent = mBtDisconnectedDevicesBlePresence.get(associationId);
            if (mBtDisconnectedDevices.contains(associationId) && isCurrentPresent) {
                mBleDeviceDisappearedScheduler.unScheduleDeviceDisappeared(associationId);
            }
        }
    }

    @Override
    public void onBleCompanionDeviceLost(int associationId, int userId) {
        Slog.i(TAG, "onBleCompanionDeviceLost " + "associationId( " + associationId + " )");
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            onDeviceLocked(associationId, userId, EVENT_BLE_APPEARED, /* ParcelUuid */ null);
            return;
        }

        onDevicePresenceEvent(mNearbyBleDevices, associationId, EVENT_BLE_DISAPPEARED);
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceEvent(int associationId, int event) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-appeared' Shell command, so the only uid-s allowed to
        // make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        // Make sure the association exists.
        enforceAssociationExists(associationId);

        final AssociationInfo associationInfo = mAssociationStore.getAssociationById(associationId);

        switch (event) {
            case EVENT_BLE_APPEARED:
                simulateDeviceAppeared(associationId, event);
                break;
            case EVENT_BT_CONNECTED:
                onBluetoothCompanionDeviceConnected(associationId, associationInfo.getUserId());
                break;
            case EVENT_BLE_DISAPPEARED:
                simulateDeviceDisappeared(associationId, event);
                break;
            case EVENT_BT_DISCONNECTED:
                onBluetoothCompanionDeviceDisconnected(associationId, associationInfo.getUserId());
                break;
            default:
                throw new IllegalArgumentException("Event: " + event + "is not supported");
        }
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceEventByUuid(ObservableUuid uuid, int event) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-uuid-events' Shell command, so the only uid-s allowed to
        // make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        onDevicePresenceEventByUuid(uuid, event);
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceEventOnDeviceLocked(
            int associationId, int userId, int event, ParcelUuid uuid) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-event-device-locked' Shell command,
        // so the only uid-s allowed to make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        onDeviceLocked(associationId, userId, event, uuid);
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceEventOnUserUnlocked(int userId) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-event-device-unlocked' Shell command,
        // so the only uid-s allowed to make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        sendDevicePresenceEventOnUnlocked(userId);
    }

    private void simulateDeviceAppeared(int associationId, int state) {
        onDevicePresenceEvent(mSimulated, associationId, state);
        mSchedulerHelper.scheduleOnDeviceGoneCallForSimulatedDevicePresence(associationId);
    }

    private void simulateDeviceDisappeared(int associationId, int state) {
        mSchedulerHelper.unscheduleOnDeviceGoneCallForSimulatedDevicePresence(associationId);
        onDevicePresenceEvent(mSimulated, associationId, state);
    }

    private void enforceAssociationExists(int associationId) {
        if (mAssociationStore.getAssociationById(associationId) == null) {
            throw new IllegalArgumentException(
                    "Association with id " + associationId + " does not exist.");
        }
    }

    private void onDevicePresenceEvent(@NonNull Set<Integer> presentDevicesForSource,
            int associationId, int eventType) {
        Slog.i(TAG,
                "onDevicePresenceEvent() id=[" + associationId + "], event=[" + eventType + "]...");

        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (association == null) {
            Slog.e(TAG, "Association doesn't exist.");
            return;
        }

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final DevicePresenceEvent event = new DevicePresenceEvent(associationId, eventType, null);

        if (eventType == EVENT_BLE_APPEARED) {
            synchronized (mBtDisconnectedDevices) {
                // If a BLE device is detected within 10 seconds after BT is disconnected,
                // flag it as BLE is present.
                if (mBtDisconnectedDevices.contains(associationId)) {
                    Slog.i(TAG, "Device ( " + associationId + " ) is present,"
                            + " do not need to send the callback with event ( "
                            + EVENT_BLE_APPEARED + " ).");
                    mBtDisconnectedDevicesBlePresence.append(associationId, true);
                }
            }
        }

        switch (eventType) {
            case EVENT_BLE_APPEARED:
            case EVENT_BT_CONNECTED:
            case EVENT_SELF_MANAGED_APPEARED:
                final boolean added = presentDevicesForSource.add(associationId);
                if (!added) {
                    Slog.w(TAG, "The association is already present.");
                }

                if (association.shouldBindWhenPresent()) {
                    bindApplicationIfNeeded(userId, packageName, association.isSelfManaged());
                } else {
                    return;
                }

                if (association.isSelfManaged() || added) {
                    notifyDevicePresenceEvent(userId, packageName, event);
                    // Also send the legacy callback.
                    legacyNotifyDevicePresenceEvent(association, true);
                }
                break;
            case EVENT_BLE_DISAPPEARED:
            case EVENT_BT_DISCONNECTED:
            case EVENT_SELF_MANAGED_DISAPPEARED:
                final boolean removed = presentDevicesForSource.remove(associationId);
                if (!removed) {
                    Slog.w(TAG, "The association is already NOT present.");
                }

                if (!mCompanionAppBinder.isCompanionApplicationBound(userId, packageName)) {
                    Slog.e(TAG, "Package is not bound");
                    return;
                }

                if (association.isSelfManaged() || removed) {
                    notifyDevicePresenceEvent(userId, packageName, event);
                    // Also send the legacy callback.
                    legacyNotifyDevicePresenceEvent(association, false);
                }

                // Check if there are other devices associated to the app that are present.
                if (!shouldBindPackage(userId, packageName)) {
                    mCompanionAppBinder.unbindCompanionApp(userId, packageName);
                }
                break;
            default:
                Slog.e(TAG, "Event: " + eventType + " is not supported.");
                break;
        }
    }

    @Override
    public void onDevicePresenceEventByUuid(ObservableUuid uuid, int eventType) {
        Slog.i(TAG, "onDevicePresenceEventByUuid ObservableUuid=[" + uuid + "], event=[" + eventType
                + "]...");

        final ParcelUuid parcelUuid = uuid.getUuid();
        final int userId = uuid.getUserId();
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            onDeviceLocked(NO_ASSOCIATION, userId, eventType, parcelUuid);
            return;
        }

        final String packageName = uuid.getPackageName();
        final DevicePresenceEvent event = new DevicePresenceEvent(NO_ASSOCIATION, eventType,
                parcelUuid);

        switch (eventType) {
            case EVENT_BT_CONNECTED:
                boolean added = mConnectedUuidDevices.add(parcelUuid);
                if (!added) {
                    Slog.w(TAG, "This device is already connected.");
                }

                bindApplicationIfNeeded(userId, packageName, false);

                notifyDevicePresenceEvent(userId, packageName, event);
                break;
            case EVENT_BT_DISCONNECTED:
                final boolean removed = mConnectedUuidDevices.remove(parcelUuid);
                if (!removed) {
                    Slog.w(TAG, "This device is already disconnected.");
                    return;
                }

                if (!mCompanionAppBinder.isCompanionApplicationBound(userId, packageName)) {
                    Slog.e(TAG, "Package is not bound.");
                    return;
                }

                notifyDevicePresenceEvent(userId, packageName, event);

                if (!shouldBindPackage(userId, packageName)) {
                    mCompanionAppBinder.unbindCompanionApp(userId, packageName);
                }
                break;
            default:
                Slog.e(TAG, "Event: " + eventType + " is not supported");
                break;
        }
    }

    /**
     * Notify device presence event to the app.
     *
     * @deprecated Use {@link #notifyDevicePresenceEvent(int, String, DevicePresenceEvent)} instead.
     */
    @Deprecated
    private void legacyNotifyDevicePresenceEvent(AssociationInfo association,
            boolean isAppeared) {
        Slog.i(TAG, "legacyNotifyDevicePresenceEvent() association=[" + association.toShortString()
                + "], isAppeared=[" + isAppeared + "]");

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        final CompanionServiceConnector primaryServiceConnector =
                mCompanionAppBinder.getPrimaryServiceConnector(userId, packageName);
        if (primaryServiceConnector == null) {
            Slog.e(TAG, "Package is not bound.");
            return;
        }

        if (isAppeared) {
            primaryServiceConnector.postOnDeviceAppeared(association);
        } else {
            primaryServiceConnector.postOnDeviceDisappeared(association);
        }
    }

    /**
     * Notify the device presence event to the app.
     */
    private void notifyDevicePresenceEvent(int userId, String packageName,
            DevicePresenceEvent event) {
        Slog.i(TAG,
                "notifyCompanionDevicePresenceEvent userId=[" + userId + "], packageName=["
                        + packageName + "], event=[" + event + "]...");

        final CompanionServiceConnector primaryServiceConnector =
                mCompanionAppBinder.getPrimaryServiceConnector(userId, packageName);

        if (primaryServiceConnector == null) {
            Slog.e(TAG, "Package is NOT bound.");
            return;
        }

        primaryServiceConnector.postOnDevicePresenceEvent(event);
    }

    /**
     * Notify the self-managed device presence event to the app.
     */
    public void notifySelfManagedDevicePresenceEvent(int associationId, boolean isAppeared) {
        Slog.i(TAG, "notifySelfManagedDeviceAppeared() id=" + associationId);

        AssociationInfo association = mAssociationStore.getAssociationWithCallerChecks(
                associationId);
        if (!association.isSelfManaged()) {
            throw new IllegalArgumentException("Association id=[" + associationId
                    + "] is not self-managed.");
        }
        // AssociationInfo class is immutable: create a new AssociationInfo object with updated
        // timestamp.
        association = (new AssociationInfo.Builder(association))
                .setLastTimeConnected(System.currentTimeMillis())
                .build();
        mAssociationStore.updateAssociation(association);

        if (isAppeared) {
            onSelfManagedDeviceConnected(associationId);
        } else {
            onSelfManagedDeviceDisconnected(associationId);
        }

        final String deviceProfile = association.getDeviceProfile();
        if (DEVICE_PROFILE_AUTOMOTIVE_PROJECTION.equals(deviceProfile)) {
            Slog.i(TAG, "Enable hint mode for device device profile: " + deviceProfile);
            mPowerManagerInternal.setPowerMode(Mode.AUTOMOTIVE_PROJECTION, isAppeared);
        }
    }

    private void onBinderDied(@UserIdInt int userId, @NonNull String packageName,
            @NonNull CompanionServiceConnector serviceConnector) {

        boolean isPrimary = serviceConnector.isPrimary();
        Slog.i(TAG, "onBinderDied() u" + userId + "/" + packageName + " isPrimary: " + isPrimary);

        // First, disable hint mode for Auto profile and mark not BOUND for primary service ONLY.
        if (isPrimary) {
            final List<AssociationInfo> associations =
                    mAssociationStore.getActiveAssociationsByPackage(userId, packageName);

            for (AssociationInfo association : associations) {
                final String deviceProfile = association.getDeviceProfile();
                if (DEVICE_PROFILE_AUTOMOTIVE_PROJECTION.equals(deviceProfile)) {
                    Slog.i(TAG, "Disable hint mode for device profile: " + deviceProfile);
                    mPowerManagerInternal.setPowerMode(Mode.AUTOMOTIVE_PROJECTION, false);
                    break;
                }
            }

            mCompanionAppBinder.removePackage(userId, packageName);
        }

        // Second: schedule rebinding if needed.
        final boolean shouldScheduleRebind = shouldScheduleRebind(userId, packageName, isPrimary);

        if (shouldScheduleRebind) {
            mCompanionAppBinder.scheduleRebinding(userId, packageName, serviceConnector);
        }
    }

    /**
     * Check if the system should rebind the self-managed secondary services
     * OR non-self-managed services.
     */
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
                mAssociationStore.getActiveAssociationsByPackage(userId, packageName)) {
            final int associationId = ai.getId();
            stillAssociated = true;
            if (ai.isSelfManaged()) {
                // Do not rebind if primary one is died for selfManaged application.
                if (isPrimary && isDevicePresent(associationId)) {
                    onSelfManagedDeviceReporterBinderDied(associationId);
                    shouldScheduleRebind = false;
                }
                // Do not rebind if both primary and secondary services are died for
                // selfManaged application.
                shouldScheduleRebind = mCompanionAppBinder.isCompanionApplicationBound(userId,
                        packageName);
            } else if (ai.isNotifyOnDeviceNearby()) {
                // Always rebind for non-selfManaged devices.
                shouldScheduleRebind = true;
            }
        }

        for (ObservableUuid uuid : uuids) {
            if (isDeviceUuidPresent(uuid.getUuid())) {
                shouldScheduleRebindForUuid = true;
                break;
            }
        }

        return (stillAssociated && shouldScheduleRebind) || shouldScheduleRebindForUuid;
    }

    /**
     * Implements
     * {@link AssociationStore.OnChangeListener#onAssociationRemoved(AssociationInfo)}
     */
    @Override
    public void onAssociationRemoved(@NonNull AssociationInfo association) {
        final int id = association.getId();

        mConnectedBtDevices.remove(id);
        mNearbyBleDevices.remove(id);
        mReportedSelfManagedDevices.remove(id);
        mSimulated.remove(id);
        synchronized (mBtDisconnectedDevices) {
            mBtDisconnectedDevices.remove(id);
            mBtDisconnectedDevicesBlePresence.delete(id);
        }

        // Do NOT call mCallback.onDeviceDisappeared()!
        // CompanionDeviceManagerService will know that the association is removed, and will do
        // what's needed.
    }


    private static void enforceCallerShellOrRoot() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == SHELL_UID || callingUid == ROOT_UID) return;

        throw new SecurityException("Caller is neither Shell nor Root");
    }

    /**
     * The BLE scan can be only stopped if all the devices have been reported
     * BT connected and BLE presence and are not pending to report BLE lost.
     */
    private boolean canStopBleScan() {
        for (AssociationInfo ai : mAssociationStore.getActiveAssociations()) {
            int id = ai.getId();
            synchronized (mBtDisconnectedDevices) {
                if (ai.isNotifyOnDeviceNearby() && !(isBtConnected(id)
                        && isBlePresent(id) && mBtDisconnectedDevices.isEmpty())) {
                    Slog.i(TAG, "The BLE scan cannot be stopped, "
                            + "device( " + id + " ) is not yet connected "
                            + "OR the BLE is not current present Or is pending to report BLE lost");
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Store the positive DevicePresenceEvent in the cache if the current device is still
     * locked.
     * Remove the current DevicePresenceEvent if there's a negative event occurs.
     */
    private void onDeviceLocked(int associationId, int userId, int event, ParcelUuid uuid) {
        switch (event) {
            case EVENT_BLE_APPEARED, EVENT_BT_CONNECTED -> {
                // Try to bind and notify the app after the phone is unlocked.
                Slog.i(TAG, "Current user is not in unlocking or unlocked stage yet. "
                        + "Notify the application when the phone is unlocked");
                synchronized (mPendingDevicePresenceEvents) {
                    final DevicePresenceEvent devicePresenceEvent = new DevicePresenceEvent(
                            associationId, event, uuid);
                    List<DevicePresenceEvent> deviceEvents = mPendingDevicePresenceEvents.get(
                            userId, new ArrayList<>());
                    deviceEvents.add(devicePresenceEvent);
                    mPendingDevicePresenceEvents.put(userId, deviceEvents);
                }
            }
            case EVENT_BLE_DISAPPEARED -> {
                synchronized (mPendingDevicePresenceEvents) {
                    List<DevicePresenceEvent> deviceEvents = mPendingDevicePresenceEvents
                            .get(userId);
                    if (deviceEvents != null) {
                        deviceEvents.removeIf(deviceEvent ->
                                deviceEvent.getEvent() == EVENT_BLE_APPEARED
                                        && Objects.equals(deviceEvent.getUuid(), uuid)
                                        && deviceEvent.getAssociationId() == associationId);
                    }
                }
            }
            case EVENT_BT_DISCONNECTED -> {
                // Do not need to report the event since the user is not unlock the
                // phone so that cdm is not bind with the app yet.
                synchronized (mPendingDevicePresenceEvents) {
                    List<DevicePresenceEvent> deviceEvents = mPendingDevicePresenceEvents
                            .get(userId);
                    if (deviceEvents != null) {
                        deviceEvents.removeIf(deviceEvent ->
                                deviceEvent.getEvent() == EVENT_BT_CONNECTED
                                        && Objects.equals(deviceEvent.getUuid(), uuid)
                                        && deviceEvent.getAssociationId() == associationId);
                    }
                }
            }
            default -> Slog.e(TAG, "Event: " + event + "is not supported");
        }
    }

    /**
     * Send the device presence event by userID when the device is unlocked.
     */
    public void sendDevicePresenceEventOnUnlocked(int userId) {
        final List<DevicePresenceEvent> deviceEvents = getPendingDevicePresenceEventsByUserId(
                userId);
        if (CollectionUtils.isEmpty(deviceEvents)) {
            return;
        }
        final List<ObservableUuid> observableUuids =
                mObservableUuidStore.getObservableUuidsForUser(userId);
        // Notify and bind the app after the phone is unlocked.
        for (DevicePresenceEvent deviceEvent : deviceEvents) {
            boolean isUuid = deviceEvent.getUuid() != null;
            if (isUuid) {
                for (ObservableUuid uuid : observableUuids) {
                    if (uuid.getUuid().equals(deviceEvent.getUuid())) {
                        onDevicePresenceEventByUuid(uuid, EVENT_BT_CONNECTED);
                    }
                }
            } else {
                int event = deviceEvent.getEvent();
                int associationId = deviceEvent.getAssociationId();
                final AssociationInfo associationInfo = mAssociationStore.getAssociationById(
                        associationId);

                if (associationInfo == null) {
                    return;
                }

                switch (event) {
                    case EVENT_BLE_APPEARED:
                        onBleCompanionDeviceFound(
                                associationInfo.getId(), associationInfo.getUserId());
                        break;
                    case EVENT_BT_CONNECTED:
                        onBluetoothCompanionDeviceConnected(
                                associationInfo.getId(), associationInfo.getUserId());
                        break;
                    default:
                        Slog.e(TAG, "Event: " + event + "is not supported");
                        break;
                }
            }
        }

        removePendingDevicePresenceEventsByUserId(userId);
    }

    private List<DevicePresenceEvent> getPendingDevicePresenceEventsByUserId(int userId) {
        synchronized (mPendingDevicePresenceEvents) {
            return mPendingDevicePresenceEvents.get(userId, new ArrayList<>());
        }
    }

    private void removePendingDevicePresenceEventsByUserId(int userId) {
        synchronized (mPendingDevicePresenceEvents) {
            if (mPendingDevicePresenceEvents.contains(userId)) {
                mPendingDevicePresenceEvents.remove(userId);
            }
        }
    }

    /**
     * Dumps system information about devices that are marked as "present".
     */
    public void dump(@NonNull PrintWriter out) {
        out.append("Companion Device Present: ");
        if (mConnectedBtDevices.isEmpty()
                && mNearbyBleDevices.isEmpty()
                && mReportedSelfManagedDevices.isEmpty()) {
            out.append("<empty>\n");
            return;
        } else {
            out.append("\n");
        }

        out.append("  Connected Bluetooth Devices: ");
        if (mConnectedBtDevices.isEmpty()) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            for (int associationId : mConnectedBtDevices) {
                AssociationInfo a = mAssociationStore.getAssociationById(associationId);
                out.append("    ").append(a.toShortString()).append('\n');
            }
        }

        out.append("  Nearby BLE Devices: ");
        if (mNearbyBleDevices.isEmpty()) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            for (int associationId : mNearbyBleDevices) {
                AssociationInfo a = mAssociationStore.getAssociationById(associationId);
                out.append("    ").append(a.toShortString()).append('\n');
            }
        }

        out.append("  Self-Reported Devices: ");
        if (mReportedSelfManagedDevices.isEmpty()) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            for (int associationId : mReportedSelfManagedDevices) {
                AssociationInfo a = mAssociationStore.getAssociationById(associationId);
                out.append("    ").append(a.toShortString()).append('\n');
            }
        }
    }

    private class SimulatedDevicePresenceSchedulerHelper extends Handler {
        SimulatedDevicePresenceSchedulerHelper() {
            super(Looper.getMainLooper());
        }

        void scheduleOnDeviceGoneCallForSimulatedDevicePresence(int associationId) {
            // First, unschedule if it was scheduled previously.
            if (hasMessages(/* what */ associationId)) {
                removeMessages(/* what */ associationId);
            }

            sendEmptyMessageDelayed(/* what */ associationId, 60 * 1000 /* 60 seconds */);
        }

        void unscheduleOnDeviceGoneCallForSimulatedDevicePresence(int associationId) {
            removeMessages(/* what */ associationId);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final int associationId = msg.what;
            if (mSimulated.contains(associationId)) {
                onDevicePresenceEvent(mSimulated, associationId, EVENT_BLE_DISAPPEARED);
            }
        }
    }

    private class BleDeviceDisappearedScheduler extends Handler {
        BleDeviceDisappearedScheduler() {
            super(Looper.getMainLooper());
        }

        void scheduleBleDeviceDisappeared(int associationId) {
            if (hasMessages(associationId)) {
                removeMessages(associationId);
            }
            Slog.i(TAG, "scheduleBleDeviceDisappeared for Device: ( " + associationId + " ).");
            sendEmptyMessageDelayed(associationId, 10 * 1000 /* 10 seconds */);
        }

        void unScheduleDeviceDisappeared(int associationId) {
            if (hasMessages(associationId)) {
                Slog.i(TAG, "unScheduleDeviceDisappeared for Device( " + associationId + " )");
                synchronized (mBtDisconnectedDevices) {
                    mBtDisconnectedDevices.remove(associationId);
                    mBtDisconnectedDevicesBlePresence.delete(associationId);
                }

                removeMessages(associationId);
            }
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final int associationId = msg.what;
            synchronized (mBtDisconnectedDevices) {
                final boolean isCurrentPresent = mBtDisconnectedDevicesBlePresence.get(
                        associationId);
                // If a device hasn't reported after 10 seconds and is not currently present,
                // assume BLE is lost and trigger the onDeviceEvent callback with the
                // EVENT_BLE_DISAPPEARED event.
                if (mBtDisconnectedDevices.contains(associationId)
                        && !isCurrentPresent) {
                    Slog.i(TAG, "Device ( " + associationId + " ) is likely BLE out of range, "
                            + "sending callback with event ( " + EVENT_BLE_DISAPPEARED + " )");
                    onDevicePresenceEvent(mNearbyBleDevices, associationId, EVENT_BLE_DISAPPEARED);
                }

                mBtDisconnectedDevices.remove(associationId);
                mBtDisconnectedDevicesBlePresence.delete(associationId);
            }
        }
    }
}
