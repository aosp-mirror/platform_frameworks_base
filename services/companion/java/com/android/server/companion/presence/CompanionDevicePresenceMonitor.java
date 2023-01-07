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

import static android.os.Process.ROOT_UID;
import static android.os.Process.SHELL_UID;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.bluetooth.BluetoothAdapter;
import android.companion.AssociationInfo;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

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
    }

    private final @NonNull AssociationStore mAssociationStore;
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

    // Tracking "simulated" presence. Used for debugging and testing only.
    private final @NonNull Set<Integer> mSimulated = new HashSet<>();
    private final SimulatedDevicePresenceSchedulerHelper mSchedulerHelper =
            new SimulatedDevicePresenceSchedulerHelper();

    public CompanionDevicePresenceMonitor(@NonNull AssociationStore associationStore,
            @NonNull Callback callback) {
        mAssociationStore = associationStore;
        mCallback = callback;

        mBtConnectionListener = new BluetoothCompanionDeviceConnectionListener(associationStore,
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
     * Marks a "self-managed" device as connected.
     *
     * <p>
     * Must ONLY be invoked by the
     * {@link com.android.server.companion.CompanionDeviceManagerService CompanionDeviceManagerService}
     * when an application invokes
     * {@link android.companion.CompanionDeviceManager#notifyDeviceAppeared(int) notifyDeviceAppeared()}
     */
    public void onSelfManagedDeviceConnected(int associationId) {
        onDevicePresent(mReportedSelfManagedDevices, associationId, "application-reported");
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
        onDeviceGone(mReportedSelfManagedDevices, associationId, "application-reported");
    }

    /**
     * Marks a "self-managed" device as disconnected when binderDied.
     */
    public void onSelfManagedDeviceReporterBinderDied(int associationId) {
        onDeviceGone(mReportedSelfManagedDevices, associationId, "application-reported");
    }

    @Override
    public void onBluetoothCompanionDeviceConnected(int associationId) {
        onDevicePresent(mConnectedBtDevices, associationId, /* sourceLoggingTag */ "bt");
    }

    @Override
    public void onBluetoothCompanionDeviceDisconnected(int associationId) {
        onDeviceGone(mConnectedBtDevices, associationId, /* sourceLoggingTag */ "bt");
    }

    @Override
    public void onBleCompanionDeviceFound(int associationId) {
        onDevicePresent(mNearbyBleDevices, associationId, /* sourceLoggingTag */ "ble");
    }

    @Override
    public void onBleCompanionDeviceLost(int associationId) {
        onDeviceGone(mNearbyBleDevices, associationId, /* sourceLoggingTag */ "ble");
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceAppeared(int associationId) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-appeared' Shell command, so the only uid-s allowed to
        // make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        // Make sure the association exists.
        enforceAssociationExists(associationId);

        onDevicePresent(mSimulated, associationId, /* sourceLoggingTag */ "simulated");

        mSchedulerHelper.scheduleOnDeviceGoneCallForSimulatedDevicePresence(associationId);
    }

    /** FOR DEBUGGING AND/OR TESTING PURPOSES ONLY. */
    @TestApi
    public void simulateDeviceDisappeared(int associationId) {
        // IMPORTANT: this API should only be invoked via the
        // 'companiondevice simulate-device-appeared' Shell command, so the only uid-s allowed to
        // make this call are SHELL and ROOT.
        // No other caller (including SYSTEM!) should be allowed.
        enforceCallerShellOrRoot();
        // Make sure the association exists.
        enforceAssociationExists(associationId);

        mSchedulerHelper.unscheduleOnDeviceGoneCallForSimulatedDevicePresence(associationId);

        onDeviceGone(mSimulated, associationId, /* sourceLoggingTag */ "simulated");
    }

    private void enforceAssociationExists(int associationId) {
        if (mAssociationStore.getAssociationById(associationId) == null) {
            throw new IllegalArgumentException(
                    "Association with id " + associationId + " does not exist.");
        }
    }

    private void onDevicePresent(@NonNull Set<Integer> presentDevicesForSource,
            int newDeviceAssociationId, @NonNull String sourceLoggingTag) {
        if (DEBUG) {
            Log.i(TAG, "onDevice_Present() id=" + newDeviceAssociationId
                    + ", source=" + sourceLoggingTag);
            Log.d(TAG, "  > association="
                    + mAssociationStore.getAssociationById(newDeviceAssociationId));
        }

        final boolean alreadyPresent = isDevicePresent(newDeviceAssociationId);
        if (DEBUG && alreadyPresent) Log.i(TAG, "Device is already present.");

        final boolean added = presentDevicesForSource.add(newDeviceAssociationId);
        if (DEBUG && !added) {
            Log.w(TAG, "Association with id " + newDeviceAssociationId + " is ALREADY reported as "
                    + "present by this source (" + sourceLoggingTag + ")");
        }

        if (alreadyPresent) return;

        mCallback.onDeviceAppeared(newDeviceAssociationId);
    }

    private void onDeviceGone(@NonNull Set<Integer> presentDevicesForSource,
            int goneDeviceAssociationId, @NonNull String sourceLoggingTag) {
        if (DEBUG) {
            Log.i(TAG, "onDevice_Gone() id=" + goneDeviceAssociationId
                    + ", source=" + sourceLoggingTag);
            Log.d(TAG, "  > association="
                    + mAssociationStore.getAssociationById(goneDeviceAssociationId));
        }

        final boolean removed = presentDevicesForSource.remove(goneDeviceAssociationId);
        if (!removed) {
            if (DEBUG) {
                Log.w(TAG, "Association with id " + goneDeviceAssociationId + " was NOT reported "
                        + "as present by this source (" + sourceLoggingTag + ")");
            }
            return;
        }

        final boolean stillPresent = isDevicePresent(goneDeviceAssociationId);
        if (stillPresent) {
            if (DEBUG) Log.i(TAG, "  Device is still present.");
            return;
        }

        mCallback.onDeviceDisappeared(goneDeviceAssociationId);
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
                onDeviceGone(mSimulated, associationId, /* sourceLoggingTag */ "simulated");
            }
        }
    }
}
