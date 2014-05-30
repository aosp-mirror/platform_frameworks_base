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
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides a way to perform Bluetooth LE advertise operations, such as start and stop
 * advertising. An advertiser can broadcast up to 31 bytes of advertisement data represented by
 * {@link AdvertisementData}.
 * <p>
 * To get an instance of {@link BluetoothLeAdvertiser}, call the
 * {@link BluetoothAdapter#getBluetoothLeAdvertiser()} method.
 * <p>
 * Note most of the methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see AdvertisementData
 */
public final class BluetoothLeAdvertiser {

    private static final String TAG = "BluetoothLeAdvertiser";

    private final IBluetoothGatt mBluetoothGatt;
    private final Handler mHandler;
    private final Map<AdvertiseCallback, AdvertiseCallbackWrapper>
            mLeAdvertisers = new HashMap<AdvertiseCallback, AdvertiseCallbackWrapper>();

    /**
     * Use BluetoothAdapter.getLeAdvertiser() instead.
     *
     * @param bluetoothGatt
     * @hide
     */
    public BluetoothLeAdvertiser(IBluetoothGatt bluetoothGatt) {
        mBluetoothGatt = bluetoothGatt;
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start Bluetooth LE Advertising. The {@code advertiseData} would be broadcasted after the
     * operation succeeds. Returns immediately, the operation status are delivered through
     * {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param settings Settings for Bluetooth LE advertising.
     * @param advertiseData Advertisement data to be broadcasted.
     * @param callback Callback for advertising status.
     */
    public void startAdvertising(AdvertiseSettings settings,
            AdvertisementData advertiseData, final AdvertiseCallback callback) {
        startAdvertising(settings, advertiseData, null, callback);
    }

    /**
     * Start Bluetooth LE Advertising. The {@code advertiseData} would be broadcasted after the
     * operation succeeds. The {@code scanResponse} would be returned when the scanning device sends
     * active scan request. Method returns immediately, the operation status are delivered through
     * {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param settings Settings for Bluetooth LE advertising.
     * @param advertiseData Advertisement data to be advertised in advertisement packet.
     * @param scanResponse Scan response associated with the advertisement data.
     * @param callback Callback for advertising status.
     */
    public void startAdvertising(AdvertiseSettings settings,
            AdvertisementData advertiseData, AdvertisementData scanResponse,
            final AdvertiseCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (mLeAdvertisers.containsKey(callback)) {
            postCallbackFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED);
            return;
        }
        AdvertiseCallbackWrapper wrapper = new AdvertiseCallbackWrapper(callback, advertiseData,
                scanResponse, settings, mBluetoothGatt);
        UUID uuid = UUID.randomUUID();
        try {
            mBluetoothGatt.registerClient(new ParcelUuid(uuid), wrapper);
            if (wrapper.advertiseStarted()) {
                mLeAdvertisers.put(callback, wrapper);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to stop advertising", e);
        }
    }

    /**
     * Stop Bluetooth LE advertising. The {@code callback} must be the same one use in
     * {@link BluetoothLeAdvertiser#startAdvertising}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} permission.
     *
     * @param callback {@link AdvertiseCallback} for delivering stopping advertising status.
     */
    public void stopAdvertising(final AdvertiseCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        AdvertiseCallbackWrapper wrapper = mLeAdvertisers.get(callback);
        if (wrapper == null) {
            postCallbackFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_NOT_STARTED);
            return;
        }
        try {
            mBluetoothGatt.stopMultiAdvertising(wrapper.mLeHandle);
            if (wrapper.advertiseStopped()) {
                mLeAdvertisers.remove(callback);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to stop advertising", e);
        }
    }

    /**
     * Bluetooth GATT interface callbacks for advertising.
     */
    private static class AdvertiseCallbackWrapper extends IBluetoothGattCallback.Stub {
        private static final int LE_CALLBACK_TIMEOUT_MILLIS = 2000;
        private final AdvertiseCallback mAdvertiseCallback;
        private final AdvertisementData mAdvertisement;
        private final AdvertisementData mScanResponse;
        private final AdvertiseSettings mSettings;
        private final IBluetoothGatt mBluetoothGatt;

        // mLeHandle 0: not registered
        // -1: scan stopped
        // >0: registered and scan started
        private int mLeHandle;
        private boolean isAdvertising = false;

        public AdvertiseCallbackWrapper(AdvertiseCallback advertiseCallback,
                AdvertisementData advertiseData, AdvertisementData scanResponse,
                AdvertiseSettings settings,
                IBluetoothGatt bluetoothGatt) {
            mAdvertiseCallback = advertiseCallback;
            mAdvertisement = advertiseData;
            mScanResponse = scanResponse;
            mSettings = settings;
            mBluetoothGatt = bluetoothGatt;
            mLeHandle = 0;
        }

        public boolean advertiseStarted() {
            boolean started = false;
            synchronized (this) {
                if (mLeHandle == -1) {
                    return false;
                }
                try {
                    wait(LE_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Callback reg wait interrupted: ", e);
                }
                started = (mLeHandle > 0 && isAdvertising);
            }
            return started;
        }

        public boolean advertiseStopped() {
            synchronized (this) {
                try {
                    wait(LE_CALLBACK_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Callback reg wait interrupted: " + e);
                }
                return !isAdvertising;
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
                    mLeHandle = clientIf;
                    try {
                        mBluetoothGatt.startMultiAdvertising(mLeHandle, mAdvertisement,
                                mScanResponse, mSettings);
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le advertise: " + e);
                        mLeHandle = -1;
                        notifyAll();
                    } catch (Exception e) {
                        Log.e(TAG, "fail to start advertise: " + e.getStackTrace());
                    }
                } else {
                    // registration failed
                    mLeHandle = -1;
                    notifyAll();
                }
            }
        }

        @Override
        public void onClientConnectionState(int status, int clientIf,
                boolean connected, String address) {
            // no op
        }

        @Override
        public void onScanResult(String address, int rssi, byte[] advData) {
            // no op
        }

        @Override
        public void onGetService(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid) {
            // no op
        }

        @Override
        public void onGetIncludedService(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int inclSrvcType, int inclSrvcInstId,
                ParcelUuid inclSrvcUuid) {
            // no op
        }

        @Override
        public void onGetCharacteristic(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int charProps) {
            // no op
        }

        @Override
        public void onGetDescriptor(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descUuid) {
            // no op
        }

        @Override
        public void onSearchComplete(String address, int status) {
            // no op
        }

        @Override
        public void onCharacteristicRead(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid, byte[] value) {
            // no op
        }

        @Override
        public void onCharacteristicWrite(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid) {
            // no op
        }

        @Override
        public void onNotify(String address, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                byte[] value) {
            // no op
        }

        @Override
        public void onDescriptorRead(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descrUuid, byte[] value) {
            // no op
        }

        @Override
        public void onDescriptorWrite(String address, int status, int srvcType,
                int srvcInstId, ParcelUuid srvcUuid,
                int charInstId, ParcelUuid charUuid,
                int descInstId, ParcelUuid descrUuid) {
            // no op
        }

        @Override
        public void onExecuteWrite(String address, int status) {
            // no op
        }

        @Override
        public void onReadRemoteRssi(String address, int rssi, int status) {
            // no op
        }

        @Override
        public void onAdvertiseStateChange(int advertiseState, int status) {
            // no op
        }

        @Override
        public void onMultiAdvertiseCallback(int status) {
            synchronized (this) {
                if (status == 0) {
                    isAdvertising = !isAdvertising;
                    if (!isAdvertising) {
                        try {
                            mBluetoothGatt.unregisterClient(mLeHandle);
                            mLeHandle = -1;
                        } catch (RemoteException e) {
                            Log.e(TAG, "remote exception when unregistering", e);
                        }
                    }
                    mAdvertiseCallback.onSuccess(null);
                } else {
                    mAdvertiseCallback.onFailure(status);
                }
                notifyAll();
            }

        }

        /**
         * Callback reporting LE ATT MTU.
         *
         * @hide
         */
        @Override
        public void onConfigureMTU(String address, int mtu, int status) {
            // no op
        }
    }

    private void postCallbackFailure(final AdvertiseCallback callback, final int error) {
        mHandler.post(new Runnable() {
                @Override
            public void run() {
                callback.onFailure(error);
            }
        });
    }
}
