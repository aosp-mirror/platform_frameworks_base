/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallbackWrapper;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides a way to perform Bluetooth LE advertise operations, such as starting and
 * stopping advertising. An advertiser can broadcast up to 31 bytes of advertisement data
 * represented by {@link AdvertiseData}.
 * <p>
 * To get an instance of {@link BluetoothLeAdvertiser}, call the
 * {@link BluetoothAdapter#getBluetoothLeAdvertiser()} method.
 * <p>
 * <b>Note:</b> Most of the methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see AdvertiseData
 */
public final class BluetoothLeAdvertiser {

    private static final String TAG = "BluetoothLeAdvertiser";

    private static final int MAX_ADVERTISING_DATA_BYTES = 31;
    // Each fields need one byte for field length and another byte for field type.
    private static final int OVERHEAD_BYTES_PER_FIELD = 2;
    // Flags field will be set by system.
    private static final int FLAGS_FIELD_BYTES = 3;
    private static final int MANUFACTURER_SPECIFIC_DATA_LENGTH = 2;
    private static final int SERVICE_DATA_UUID_LENGTH = 2;

    private final IBluetoothManager mBluetoothManager;
    private final Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private final Map<AdvertiseCallback, AdvertiseCallbackWrapper>
            mLeAdvertisers = new HashMap<AdvertiseCallback, AdvertiseCallbackWrapper>();

    /**
     * Use BluetoothAdapter.getLeAdvertiser() instead.
     *
     * @param bluetoothManager BluetoothManager that conducts overall Bluetooth Management
     * @hide
     */
    public BluetoothLeAdvertiser(IBluetoothManager bluetoothManager) {
        mBluetoothManager = bluetoothManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start Bluetooth LE Advertising. On success, the {@code advertiseData} will be broadcasted.
     * Returns immediately, the operation status is delivered through {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param settings Settings for Bluetooth LE advertising.
     * @param advertiseData Advertisement data to be broadcasted.
     * @param callback Callback for advertising status.
     */
    public void startAdvertising(AdvertiseSettings settings,
            AdvertiseData advertiseData, final AdvertiseCallback callback) {
        startAdvertising(settings, advertiseData, null, callback);
    }

    /**
     * Start Bluetooth LE Advertising. The {@code advertiseData} will be broadcasted if the
     * operation succeeds. The {@code scanResponse} is returned when a scanning device sends an
     * active scan request. This method returns immediately, the operation status is delivered
     * through {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param settings Settings for Bluetooth LE advertising.
     * @param advertiseData Advertisement data to be advertised in advertisement packet.
     * @param scanResponse Scan response associated with the advertisement data.
     * @param callback Callback for advertising status.
     */
    public void startAdvertising(AdvertiseSettings settings,
            AdvertiseData advertiseData, AdvertiseData scanResponse,
            final AdvertiseCallback callback) {
        synchronized (mLeAdvertisers) {
            BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            if (!mBluetoothAdapter.isMultipleAdvertisementSupported() &&
                    !mBluetoothAdapter.isPeripheralModeSupported()) {
                postStartFailure(callback,
                        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED);
                return;
            }
            boolean isConnectable = settings.isConnectable();
            if (totalBytes(advertiseData, isConnectable) > MAX_ADVERTISING_DATA_BYTES ||
                    totalBytes(scanResponse, false) > MAX_ADVERTISING_DATA_BYTES) {
                postStartFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
                return;
            }
            if (mLeAdvertisers.containsKey(callback)) {
                postStartFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED);
                return;
            }

            IBluetoothGatt gatt;
            try {
                gatt = mBluetoothManager.getBluetoothGatt();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get Bluetooth gatt - ", e);
                postStartFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR);
                return;
            }
            AdvertiseCallbackWrapper wrapper = new AdvertiseCallbackWrapper(callback, advertiseData,
                    scanResponse, settings, gatt);
            wrapper.startRegisteration();
        }
    }

    /**
     * Stop Bluetooth LE advertising. The {@code callback} must be the same one use in
     * {@link BluetoothLeAdvertiser#startAdvertising}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback {@link AdvertiseCallback} identifies the advertising instance to stop.
     */
    public void stopAdvertising(final AdvertiseCallback callback) {
        synchronized (mLeAdvertisers) {
            BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            AdvertiseCallbackWrapper wrapper = mLeAdvertisers.get(callback);
            if (wrapper == null) return;
            wrapper.stopAdvertising();
        }
    }

    /**
     * Cleans up advertise clients. Should be called when bluetooth is down.
     *
     * @hide
     */
    public void cleanup() {
        mLeAdvertisers.clear();
    }

    // Compute the size of advertisement data or scan resp
    private int totalBytes(AdvertiseData data, boolean isFlagsIncluded) {
        if (data == null) return 0;
        // Flags field is omitted if the advertising is not connectable.
        int size = (isFlagsIncluded) ? FLAGS_FIELD_BYTES : 0;
        if (data.getServiceUuids() != null) {
            int num16BitUuids = 0;
            int num32BitUuids = 0;
            int num128BitUuids = 0;
            for (ParcelUuid uuid : data.getServiceUuids()) {
                if (BluetoothUuid.is16BitUuid(uuid)) {
                    ++num16BitUuids;
                } else if (BluetoothUuid.is32BitUuid(uuid)) {
                    ++num32BitUuids;
                } else {
                    ++num128BitUuids;
                }
            }
            // 16 bit service uuids are grouped into one field when doing advertising.
            if (num16BitUuids != 0) {
                size += OVERHEAD_BYTES_PER_FIELD +
                        num16BitUuids * BluetoothUuid.UUID_BYTES_16_BIT;
            }
            // 32 bit service uuids are grouped into one field when doing advertising.
            if (num32BitUuids != 0) {
                size += OVERHEAD_BYTES_PER_FIELD +
                        num32BitUuids * BluetoothUuid.UUID_BYTES_32_BIT;
            }
            // 128 bit service uuids are grouped into one field when doing advertising.
            if (num128BitUuids != 0) {
                size += OVERHEAD_BYTES_PER_FIELD +
                        num128BitUuids * BluetoothUuid.UUID_BYTES_128_BIT;
            }
        }
        for (ParcelUuid uuid : data.getServiceData().keySet()) {
            size += OVERHEAD_BYTES_PER_FIELD + SERVICE_DATA_UUID_LENGTH
                    + byteLength(data.getServiceData().get(uuid));
        }
        for (int i = 0; i < data.getManufacturerSpecificData().size(); ++i) {
            size += OVERHEAD_BYTES_PER_FIELD + MANUFACTURER_SPECIFIC_DATA_LENGTH +
                    byteLength(data.getManufacturerSpecificData().valueAt(i));
        }
        if (data.getIncludeTxPowerLevel()) {
            size += OVERHEAD_BYTES_PER_FIELD + 1; // tx power level value is one byte.
        }
        if (data.getIncludeDeviceName() && mBluetoothAdapter.getName() != null) {
            size += OVERHEAD_BYTES_PER_FIELD + mBluetoothAdapter.getName().length();
        }
        return size;
    }

    private int byteLength(byte[] array) {
        return array == null ? 0 : array.length;
    }

    /**
     * Bluetooth GATT interface callbacks for advertising.
     */
    private class AdvertiseCallbackWrapper extends BluetoothGattCallbackWrapper {
        private static final int LE_CALLBACK_TIMEOUT_MILLIS = 2000;
        private final AdvertiseCallback mAdvertiseCallback;
        private final AdvertiseData mAdvertisement;
        private final AdvertiseData mScanResponse;
        private final AdvertiseSettings mSettings;
        private final IBluetoothGatt mBluetoothGatt;

        // mClientIf 0: not registered
        // -1: scan stopped
        // >0: registered and scan started
        private int mClientIf;
        private boolean mIsAdvertising = false;

        public AdvertiseCallbackWrapper(AdvertiseCallback advertiseCallback,
                AdvertiseData advertiseData, AdvertiseData scanResponse,
                AdvertiseSettings settings,
                IBluetoothGatt bluetoothGatt) {
            mAdvertiseCallback = advertiseCallback;
            mAdvertisement = advertiseData;
            mScanResponse = scanResponse;
            mSettings = settings;
            mBluetoothGatt = bluetoothGatt;
            mClientIf = 0;
        }

        public void startRegisteration() {
            synchronized (this) {
                if (mClientIf == -1) return;

                try {
                    UUID uuid = UUID.randomUUID();
                    mBluetoothGatt.registerClient(new ParcelUuid(uuid), this);
                    wait(LE_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException | RemoteException e) {
                    Log.e(TAG, "Failed to start registeration", e);
                }
                if (mClientIf > 0 && mIsAdvertising) {
                    mLeAdvertisers.put(mAdvertiseCallback, this);
                } else if (mClientIf <= 0) {
                    // Post internal error if registration failed.
                    postStartFailure(mAdvertiseCallback,
                            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR);
                } else {
                    // Unregister application if it's already registered but advertise failed.
                    try {
                        mBluetoothGatt.unregisterClient(mClientIf);
                        mClientIf = -1;
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote exception when unregistering", e);
                    }
                }
            }
        }

        public void stopAdvertising() {
            synchronized (this) {
                try {
                    mBluetoothGatt.stopMultiAdvertising(mClientIf);
                    wait(LE_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException | RemoteException e) {
                    Log.e(TAG, "Failed to stop advertising", e);
                }
                // Advertise callback should have been removed from LeAdvertisers when
                // onMultiAdvertiseCallback was called. In case onMultiAdvertiseCallback is never
                // invoked and wait timeout expires, remove callback here.
                if (mLeAdvertisers.containsKey(mAdvertiseCallback)) {
                    mLeAdvertisers.remove(mAdvertiseCallback);
                }
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        @Override
        public void onClientRegistered(int status, int clientIf) {
            Log.d(TAG, "onClientRegistered() - status=" + status + " clientIf=" + clientIf);
            synchronized (this) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mClientIf = clientIf;
                    try {
                        mBluetoothGatt.startMultiAdvertising(mClientIf, mAdvertisement,
                                mScanResponse, mSettings);
                        return;
                    } catch (RemoteException e) {
                        Log.e(TAG, "failed to start advertising", e);
                    }
                }
                // Registration failed.
                mClientIf = -1;
                notifyAll();
            }
        }

        @Override
        public void onMultiAdvertiseCallback(int status, boolean isStart,
                AdvertiseSettings settings) {
            synchronized (this) {
                if (isStart) {
                    if (status == AdvertiseCallback.ADVERTISE_SUCCESS) {
                        // Start success
                        mIsAdvertising = true;
                        postStartSuccess(mAdvertiseCallback, settings);
                    } else {
                        // Start failure.
                        postStartFailure(mAdvertiseCallback, status);
                    }
                } else {
                    // unregister client for stop.
                    try {
                        mBluetoothGatt.unregisterClient(mClientIf);
                        mClientIf = -1;
                        mIsAdvertising = false;
                        mLeAdvertisers.remove(mAdvertiseCallback);
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote exception when unregistering", e);
                    }
                }
                notifyAll();
            }

        }
    }

    private void postStartFailure(final AdvertiseCallback callback, final int error) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onStartFailure(error);
            }
        });
    }

    private void postStartSuccess(final AdvertiseCallback callback,
            final AdvertiseSettings settings) {
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                callback.onStartSuccess(settings);
            }
        });
    }
}
