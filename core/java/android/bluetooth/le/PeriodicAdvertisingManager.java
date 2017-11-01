/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.bluetooth.le;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * This class provides methods to perform periodic advertising related
 * operations. An application can register for periodic advertisements using
 * {@link PeriodicAdvertisingManager#registerSync}.
 * <p>
 * Use {@link BluetoothAdapter#getPeriodicAdvertisingManager()} to get an
 * instance of {@link PeriodicAdvertisingManager}.
 * <p>
 * <b>Note:</b> Most of the methods here require
 * {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
 *
 * @hide
 */
public final class PeriodicAdvertisingManager {

    private static final String TAG = "PeriodicAdvertisingManager";

    private static final int SKIP_MIN = 0;
    private static final int SKIP_MAX = 499;
    private static final int TIMEOUT_MIN = 10;
    private static final int TIMEOUT_MAX = 16384;

    private static final int SYNC_STARTING = -1;

    private final IBluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    /* maps callback, to callback wrapper and sync handle */
    Map<PeriodicAdvertisingCallback,
            IPeriodicAdvertisingCallback /* callbackWrapper */> mCallbackWrappers;

    /**
     * Use {@link BluetoothAdapter#getBluetoothLeScanner()} instead.
     *
     * @param bluetoothManager BluetoothManager that conducts overall Bluetooth Management.
     * @hide
     */
    public PeriodicAdvertisingManager(IBluetoothManager bluetoothManager) {
        mBluetoothManager = bluetoothManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mCallbackWrappers = new IdentityHashMap<>();
    }

    /**
     * Synchronize with periodic advertising pointed to by the {@code scanResult}.
     * The {@code scanResult} used must contain a valid advertisingSid. First
     * call to registerSync will use the {@code skip} and {@code timeout} provided.
     * Subsequent calls from other apps, trying to sync with same set will reuse
     * existing sync, thus {@code skip} and {@code timeout} values will not take
     * effect. The values in effect will be returned in
     * {@link PeriodicAdvertisingCallback#onSyncEstablished}.
     *
     * @param scanResult Scan result containing advertisingSid.
     * @param skip The number of periodic advertising packets that can be skipped after a successful
     * receive. Must be between 0 and 499.
     * @param timeout Synchronization timeout for the periodic advertising. One unit is 10ms. Must
     * be between 10 (100ms) and 16384 (163.84s).
     * @param callback Callback used to deliver all operations status.
     * @throws IllegalArgumentException if {@code scanResult} is null or {@code skip} is invalid or
     * {@code timeout} is invalid or {@code callback} is null.
     */
    public void registerSync(ScanResult scanResult, int skip, int timeout,
            PeriodicAdvertisingCallback callback) {
        registerSync(scanResult, skip, timeout, callback, null);
    }

    /**
     * Synchronize with periodic advertising pointed to by the {@code scanResult}.
     * The {@code scanResult} used must contain a valid advertisingSid. First
     * call to registerSync will use the {@code skip} and {@code timeout} provided.
     * Subsequent calls from other apps, trying to sync with same set will reuse
     * existing sync, thus {@code skip} and {@code timeout} values will not take
     * effect. The values in effect will be returned in
     * {@link PeriodicAdvertisingCallback#onSyncEstablished}.
     *
     * @param scanResult Scan result containing advertisingSid.
     * @param skip The number of periodic advertising packets that can be skipped after a successful
     * receive. Must be between 0 and 499.
     * @param timeout Synchronization timeout for the periodic advertising. One unit is 10ms. Must
     * be between 10 (100ms) and 16384 (163.84s).
     * @param callback Callback used to deliver all operations status.
     * @param handler thread upon which the callbacks will be invoked.
     * @throws IllegalArgumentException if {@code scanResult} is null or {@code skip} is invalid or
     * {@code timeout} is invalid or {@code callback} is null.
     */
    public void registerSync(ScanResult scanResult, int skip, int timeout,
            PeriodicAdvertisingCallback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback can't be null");
        }

        if (scanResult == null) {
            throw new IllegalArgumentException("scanResult can't be null");
        }

        if (scanResult.getAdvertisingSid() == ScanResult.SID_NOT_PRESENT) {
            throw new IllegalArgumentException("scanResult must contain a valid sid");
        }

        if (skip < SKIP_MIN || skip > SKIP_MAX) {
            throw new IllegalArgumentException(
                    "timeout must be between " + TIMEOUT_MIN + " and " + TIMEOUT_MAX);
        }

        if (timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
            throw new IllegalArgumentException(
                    "timeout must be between " + TIMEOUT_MIN + " and " + TIMEOUT_MAX);
        }

        IBluetoothGatt gatt;
        try {
            gatt = mBluetoothManager.getBluetoothGatt();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get Bluetooth gatt - ", e);
            callback.onSyncEstablished(0, scanResult.getDevice(), scanResult.getAdvertisingSid(),
                    skip, timeout,
                    PeriodicAdvertisingCallback.SYNC_NO_RESOURCES);
            return;
        }

        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }

        IPeriodicAdvertisingCallback wrapped = wrap(callback, handler);
        mCallbackWrappers.put(callback, wrapped);

        try {
            gatt.registerSync(scanResult, skip, timeout, wrapped);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register sync - ", e);
            return;
        }
    }

    /**
     * Cancel pending attempt to create sync, or terminate existing sync.
     *
     * @param callback Callback used to deliver all operations status.
     * @throws IllegalArgumentException if {@code callback} is null, or not a properly registered
     * callback.
     */
    public void unregisterSync(PeriodicAdvertisingCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback can't be null");
        }

        IBluetoothGatt gatt;
        try {
            gatt = mBluetoothManager.getBluetoothGatt();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get Bluetooth gatt - ", e);
            return;
        }

        IPeriodicAdvertisingCallback wrapper = mCallbackWrappers.remove(callback);
        if (wrapper == null) {
            throw new IllegalArgumentException("callback was not properly registered");
        }

        try {
            gatt.unregisterSync(wrapper);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to cancel sync creation - ", e);
            return;
        }
    }

    private IPeriodicAdvertisingCallback wrap(PeriodicAdvertisingCallback callback,
            Handler handler) {
        return new IPeriodicAdvertisingCallback.Stub() {
            public void onSyncEstablished(int syncHandle, BluetoothDevice device,
                    int advertisingSid, int skip, int timeout, int status) {

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSyncEstablished(syncHandle, device, advertisingSid, skip,
                                timeout,
                                status);

                        if (status != PeriodicAdvertisingCallback.SYNC_SUCCESS) {
                            // App can still unregister the sync until notified it failed. Remove
                            // callback
                            // after app was notifed.
                            mCallbackWrappers.remove(callback);
                        }
                    }
                });
            }

            public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onPeriodicAdvertisingReport(report);
                    }
                });
            }

            public void onSyncLost(int syncHandle) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSyncLost(syncHandle);
                        // App can still unregister the sync until notified it's lost.
                        // Remove callback after app was notifed.
                        mCallbackWrappers.remove(callback);
                    }
                });
            }
        };
    }
}
