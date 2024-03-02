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

import static android.companion.DevicePresenceEvent.EVENT_BLE_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BLE_DISAPPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BT_CONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_BT_DISCONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SHELL_UID;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.companion.AssociationStore;

import java.io.PrintWriter;
import java.util.HashSet;
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
 * <li> {@link Callback#onDeviceAppeared(int) Callback.onDeviceAppeared(int)}
 * <li> {@link Callback#onDeviceDisappeared(int) Callback.onDeviceDisappeared(int)}
 * <li> {@link Callback#onDevicePresenceEvent(int, int)}}
 * </ul>
 */
@SuppressLint("LongLogTag")
public class CompanionDevicePresenceMonitor implements AssociationStore.OnChangeListener,
        BluetoothCompanionDeviceConnectionListener.Callback, BleCompanionDeviceScanner.Callback {
    static final boolean DEBUG = false;
    private static final String TAG = "CDM_CompanionDevicePresenceMonitor";

    /** Callback for notifying about changes to status of companion devices. */
    public interface Callback {
        /** Invoked when companion device is found nearby or connects. */
        void onDeviceAppeared(int associationId);

        /** Invoked when a companion device no longer seen nearby or disconnects. */
        void onDeviceDisappeared(int associationId);

        /** Invoked when device has corresponding event changes. */
        void onDevicePresenceEvent(int associationId, int event);

        /** Invoked when device has corresponding event changes base on the UUID */
        void onDevicePresenceEventByUuid(ObservableUuid uuid, int event);
    }

    private final @NonNull AssociationStore mAssociationStore;
    private final @NonNull ObservableUuidStore mObservableUuidStore;
    private final @NonNull Callback mCallback;
    private final @NonNull BluetoothCompanionDeviceConnectionListener mBtConnectionListener;
    private final @NonNull BleCompanionDeviceScanner mBleScanner;

    // NOTE: Same association may appear in more than one of the following sets at the same time.
    // (E.g. self-managed devices that have MAC addresses, could be reported as present by their
    // companion applications, while at the same be connected via BT, or detected nearby by BLE
    // scanner)
    private final @NonNull Set<Integer> mConnectedBtDevices = new HashSet<>();
    private final @NonNull Set<Integer> mNearbyBleDevices = new HashSet<>();
    private final @NonNull Set<Integer> mReportedSelfManagedDevices = new HashSet<>();
    private final @NonNull Set<ParcelUuid> mConnectedUuidDevices = new HashSet<>();
    @GuardedBy("mBtDisconnectedDevices")
    private final @NonNull Set<Integer> mBtDisconnectedDevices = new HashSet<>();

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

    public CompanionDevicePresenceMonitor(UserManager userManager,
            @NonNull AssociationStore associationStore,
            @NonNull ObservableUuidStore observableUuidStore, @NonNull Callback callback) {
        mAssociationStore = associationStore;
        mObservableUuidStore = observableUuidStore;
        mCallback = callback;
        mBtConnectionListener = new BluetoothCompanionDeviceConnectionListener(userManager,
                associationStore, mObservableUuidStore,
                /* BluetoothCompanionDeviceConnectionListener.Callback */ this);
        mBleScanner = new BleCompanionDeviceScanner(associationStore,
                /* BleCompanionDeviceScanner.Callback */ this);
    }

    /** Initialize {@link CompanionDevicePresenceMonitor} */
    public void init(Context context) {
        if (DEBUG) Log.i(TAG, "init()");

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            mBtConnectionListener.init(btAdapter);
            mBleScanner.init(context, btAdapter);
        } else {
            Log.w(TAG, "BluetoothAdapter is NOT available.");
        }

        mAssociationStore.registerListener(this);
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
     *         or devices is connected (for Bluetooth); or reported (by the application) to be
     *         nearby (for "self-managed" associations).
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
     * {@link com.android.server.companion.CompanionDeviceManagerService CompanionDeviceManagerService}
     * when an application invokes
     * {@link android.companion.CompanionDeviceManager#notifyDeviceAppeared(int) notifyDeviceAppeared()}
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
     * {@link com.android.server.companion.CompanionDeviceManagerService CompanionDeviceManagerService}
     * when an application invokes
     * {@link android.companion.CompanionDeviceManager#notifyDeviceDisappeared(int) notifyDeviceDisappeared()}
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
    public void onBluetoothCompanionDeviceConnected(int associationId) {
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
                mBleScanner.stopScanIfNeeded();
            }

        }
    }

    @Override
    public void onBluetoothCompanionDeviceDisconnected(int associationId) {
        Slog.i(TAG, "onBluetoothCompanionDeviceDisconnected "
                + "associationId( " + associationId + " )");
        // Start BLE scanning when the device is disconnected.
        mBleScanner.startScan();

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
    public void onDevicePresenceEventByUuid(ObservableUuid uuid, int event) {
        final ParcelUuid parcelUuid = uuid.getUuid();

        switch(event) {
            case EVENT_BT_CONNECTED:
                boolean added = mConnectedUuidDevices.add(parcelUuid);

                if (!added) {
                    Slog.w(TAG, "Uuid= " + parcelUuid + "is ALREADY reported as "
                            + "present by this event=" + event);
                }

                break;
            case EVENT_BT_DISCONNECTED:
                final boolean removed = mConnectedUuidDevices.remove(parcelUuid);

                if (!removed) {
                    Slog.w(TAG, "UUID= " + parcelUuid + " was NOT reported "
                            + "as present by this event= " + event);

                    return;
                }

                break;
        }

        mCallback.onDevicePresenceEventByUuid(uuid, event);
    }


    @Override
    public void onBleCompanionDeviceFound(int associationId) {
        onDevicePresenceEvent(mNearbyBleDevices, associationId, EVENT_BLE_APPEARED);
        synchronized (mBtDisconnectedDevices) {
            final boolean isCurrentPresent = mBtDisconnectedDevicesBlePresence.get(associationId);
            if (mBtDisconnectedDevices.contains(associationId) && isCurrentPresent) {
                mBleDeviceDisappearedScheduler.unScheduleDeviceDisappeared(associationId);
            }
        }
    }

    @Override
    public void onBleCompanionDeviceLost(int associationId) {
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

        switch (event) {
            case EVENT_BLE_APPEARED:
                simulateDeviceAppeared(associationId, event);
                break;
            case EVENT_BT_CONNECTED:
                onBluetoothCompanionDeviceConnected(associationId);
                break;
            case EVENT_BLE_DISAPPEARED:
                simulateDeviceDisappeared(associationId, event);
                break;
            case EVENT_BT_DISCONNECTED:
                onBluetoothCompanionDeviceDisconnected(associationId);
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
            int associationId, int event) {
        Slog.i(TAG, "onDevicePresenceEvent() id=" + associationId + ", event=" + event);

        switch (event) {
            case EVENT_BLE_APPEARED:
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
            case EVENT_BT_CONNECTED:
            case EVENT_SELF_MANAGED_APPEARED:
                final boolean added = presentDevicesForSource.add(associationId);

                if (!added) {
                    Slog.w(TAG, "Association with id "
                            + associationId + " is ALREADY reported as "
                            + "present by this source, event=" + event);
                }

                mCallback.onDeviceAppeared(associationId);

                break;
            case EVENT_BLE_DISAPPEARED:
            case EVENT_BT_DISCONNECTED:
            case EVENT_SELF_MANAGED_DISAPPEARED:
                final boolean removed = presentDevicesForSource.remove(associationId);

                if (!removed) {
                    Slog.w(TAG, "Association with id " + associationId + " was NOT reported "
                            + "as present by this source, event= " + event);

                    return;
                }

                mCallback.onDeviceDisappeared(associationId);

                break;
            default:
                Slog.e(TAG, "Event: " + event + " is not supported");
                return;
        }

        mCallback.onDevicePresenceEvent(associationId, event);
    }

    /**
     * Implements
     * {@link AssociationStore.OnChangeListener#onAssociationRemoved(AssociationInfo)}
     */
    @Override
    public void onAssociationRemoved(@NonNull AssociationInfo association) {
        final int id = association.getId();
        if (DEBUG) {
            Log.i(TAG, "onAssociationRemoved() id=" + id);
            Log.d(TAG, "  > association=" + association);
        }

        mConnectedBtDevices.remove(id);
        mNearbyBleDevices.remove(id);
        mReportedSelfManagedDevices.remove(id);
        mSimulated.remove(id);
        mBtDisconnectedDevices.remove(id);
        mBtDisconnectedDevicesBlePresence.delete(id);

        // Do NOT call mCallback.onDeviceDisappeared()!
        // CompanionDeviceManagerService will know that the association is removed, and will do
        // what's needed.
    }

    /**
     * Return a set of devices that pending to report connectivity
     */
    public SparseArray<Set<BluetoothDevice>> getPendingConnectedDevices() {
        synchronized (mBtConnectionListener.mPendingConnectedDevices) {
            return mBtConnectionListener.mPendingConnectedDevices;
        }
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
        for (AssociationInfo ai : mAssociationStore.getAssociations()) {
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
            sendEmptyMessageDelayed(associationId,  10 * 1000 /* 10 seconds */);
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
