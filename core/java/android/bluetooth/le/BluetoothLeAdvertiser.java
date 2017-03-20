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
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.le.IAdvertiserCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
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
    private final Map<AdvertiseCallback, AdvertisingSetCallback>
            mLegacyAdvertisers = new HashMap<>();
    private final Map<AdvertisingSetCallback, IAdvertisingSetCallback>
            mCallbackWrappers = Collections.synchronizedMap(new HashMap<>());
    private final Map<Integer, AdvertisingSet>
            mAdvertisingSets = Collections.synchronizedMap(new HashMap<>());

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
        synchronized (mLegacyAdvertisers) {
            BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            boolean isConnectable = settings.isConnectable();
            if (totalBytes(advertiseData, isConnectable) > MAX_ADVERTISING_DATA_BYTES ||
                    totalBytes(scanResponse, false) > MAX_ADVERTISING_DATA_BYTES) {
                postStartFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE);
                return;
            }
            if (mLegacyAdvertisers.containsKey(callback)) {
                postStartFailure(callback, AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED);
                return;
            }

            AdvertisingSetParameters.Builder parameters = new AdvertisingSetParameters.Builder();
            parameters.setLegacyMode(true);
            parameters.setConnectable(isConnectable);
            if (settings.getMode() == AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) {
                parameters.setInterval(1600); // 1s
            } else if (settings.getMode() == AdvertiseSettings.ADVERTISE_MODE_BALANCED) {
                parameters.setInterval(400); // 250ms
            } else if (settings.getMode() == AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) {
                parameters.setInterval(160); // 100ms
            }

            if (settings.getTxPowerLevel() == AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW) {
                parameters.setTxPowerLevel(-21);
            } else if (settings.getTxPowerLevel() == AdvertiseSettings.ADVERTISE_TX_POWER_LOW) {
                parameters.setTxPowerLevel(-15);
            } else if (settings.getTxPowerLevel() == AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM) {
                parameters.setTxPowerLevel(-7);
            } else if (settings.getTxPowerLevel() == AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) {
                parameters.setTxPowerLevel(1);
            }

            AdvertisingSetCallback wrapped = wrapOldCallback(callback, settings);
            mLegacyAdvertisers.put(callback, wrapped);
            startAdvertisingSet(parameters.build(), advertiseData, scanResponse, null, null,
                                settings.getTimeout(), wrapped);
        }
    }

    AdvertisingSetCallback wrapOldCallback(AdvertiseCallback callback, AdvertiseSettings settings) {
        return new AdvertisingSetCallback() {
            public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int status) {
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    postStartFailure(callback, status);
                    return;
                }

                postStartSuccess(callback, settings);
            }

            /* Legacy advertiser is disabled on timeout */
            public void onAdvertisingEnabled(int advertiserId, boolean enabled, int status) {
                if (enabled == true) {
                    Log.e(TAG, "Legacy advertiser should be only disabled on timeout," +
                        " but was enabled!");
                    return;
                }

                stopAdvertising(callback);
            }

        };
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
        synchronized (mLegacyAdvertisers) {
            if (callback == null) {
                throw new IllegalArgumentException("callback cannot be null");
            }
            AdvertisingSetCallback wrapper = mLegacyAdvertisers.get(callback);
            if (wrapper == null) return;

            stopAdvertisingSet(wrapper);
        }
    }

    /**
    * Creates a new advertising set. If operation succeed, device will start advertising. This
    * method returns immediately, the operation status is delivered through
    * {@code callback.onAdvertisingSetStarted()}.
    * <p>
    * @param parameters advertising set parameters.
    * @param advertiseData Advertisement data to be broadcasted.
    * @param scanResponse Scan response associated with the advertisement data.
    * @param periodicData Periodic advertising data.
    * @param callback Callback for advertising set.
    */
    public void startAdvertisingSet(AdvertisingSetParameters parameters,
                                    AdvertiseData advertiseData, AdvertiseData scanResponse,
                                    PeriodicAdvertisingParameters periodicParameters,
                                    AdvertiseData periodicData, AdvertisingSetCallback callback) {
            startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                            periodicData, 0, callback, new Handler(Looper.getMainLooper()));
    }

    /**
    * Creates a new advertising set. If operation succeed, device will start advertising. This
    * method returns immediately, the operation status is delivered through
    * {@code callback.onAdvertisingSetStarted()}.
    * <p>
    * @param parameters advertising set parameters.
    * @param advertiseData Advertisement data to be broadcasted.
    * @param scanResponse Scan response associated with the advertisement data.
    * @param periodicData Periodic advertising data.
    * @param callback Callback for advertising set.
    * @param handler thread upon which the callbacks will be invoked.
    */
    public void startAdvertisingSet(AdvertisingSetParameters parameters,
                                    AdvertiseData advertiseData, AdvertiseData scanResponse,
                                    PeriodicAdvertisingParameters periodicParameters,
                                    AdvertiseData periodicData, AdvertisingSetCallback callback,
                                    Handler handler) {
        startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                            periodicData, 0, callback, handler);
    }

    /**
    * Creates a new advertising set. If operation succeed, device will start advertising. This
    * method returns immediately, the operation status is delivered through
    * {@code callback.onAdvertisingSetStarted()}.
    * <p>
    * @param parameters advertising set parameters.
    * @param advertiseData Advertisement data to be broadcasted.
    * @param scanResponse Scan response associated with the advertisement data.
    * @param periodicData Periodic advertising data.
    * @param timeoutMillis Advertising time limit. May not exceed 180000
    * @param callback Callback for advertising set.
    */
    public void startAdvertisingSet(AdvertisingSetParameters parameters,
                                    AdvertiseData advertiseData, AdvertiseData scanResponse,
                                    PeriodicAdvertisingParameters periodicParameters,
                                    AdvertiseData periodicData, int timeoutMillis,
                                    AdvertisingSetCallback callback) {
        startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                            periodicData, timeoutMillis, callback, new Handler(Looper.getMainLooper()));
    }

    /**
    * Creates a new advertising set. If operation succeed, device will start advertising. This
    * method returns immediately, the operation status is delivered through
    * {@code callback.onAdvertisingSetStarted()}.
    * <p>
    * @param parameters advertising set parameters.
    * @param advertiseData Advertisement data to be broadcasted.
    * @param scanResponse Scan response associated with the advertisement data.
    * @param periodicData Periodic advertising data.
    * @param timeoutMillis Advertising time limit. May not exceed 180000
    * @param callback Callback for advertising set.
    * @param handler thread upon which the callbacks will be invoked.
    */
    public void startAdvertisingSet(AdvertisingSetParameters parameters,
                                    AdvertiseData advertiseData, AdvertiseData scanResponse,
                                    PeriodicAdvertisingParameters periodicParameters,
                                    AdvertiseData periodicData, int timeoutMillis,
                                    AdvertisingSetCallback callback, Handler handler) {
        BluetoothLeUtils.checkAdapterStateOn(mBluetoothAdapter);

        if (callback == null) {
          throw new IllegalArgumentException("callback cannot be null");
        }

        IBluetoothGatt gatt;
        try {
          gatt = mBluetoothManager.getBluetoothGatt();
        } catch (RemoteException e) {
          Log.e(TAG, "Failed to get Bluetooth gatt - ", e);
          throw new IllegalStateException("Failed to get Bluetooth");
        }

        IAdvertisingSetCallback wrapped = wrap(callback, handler);
        if (mCallbackWrappers.putIfAbsent(callback, wrapped) != null) {
            throw new IllegalArgumentException(
                "callback instance already associated with advertising");
        }

        try {
            gatt.startAdvertisingSet(parameters, advertiseData, scanResponse, periodicParameters,
                                     periodicData, timeoutMillis, wrapped);
        } catch (RemoteException e) {
          Log.e(TAG, "Failed to start advertising set - ", e);
          throw new IllegalStateException("Failed to start advertising set");
        }
    }

    /**
     * Used to dispose of a {@link AdvertisingSet} object, obtained with {@link
     * BluetoothLeAdvertiser#startAdvertisingSet}.
     */
    public void stopAdvertisingSet(AdvertisingSetCallback callback) {
        if (callback == null) {
          throw new IllegalArgumentException("callback cannot be null");
        }

        IAdvertisingSetCallback wrapped = mCallbackWrappers.remove(callback);
        if (wrapped == null) {
            return;
        }

        IBluetoothGatt gatt;
        try {
            gatt = mBluetoothManager.getBluetoothGatt();
            gatt.stopAdvertisingSet(wrapped);
       } catch (RemoteException e) {
            Log.e(TAG, "Failed to stop advertising - ", e);
            throw new IllegalStateException("Failed to stop advertising");
        }
    }

    /**
     * Cleans up advertisers. Should be called when bluetooth is down.
     *
     * @hide
     */
    public void cleanup() {
        mLegacyAdvertisers.clear();
        mCallbackWrappers.clear();
        mAdvertisingSets.clear();
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

    IAdvertisingSetCallback wrap(AdvertisingSetCallback callback, Handler handler) {
        return new IAdvertisingSetCallback.Stub() {
            public void onAdvertisingSetStarted(int advertiserId, int txPower, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                            callback.onAdvertisingSetStarted(null, 0, status);
                            mCallbackWrappers.remove(callback);
                            return;
                        }

                        AdvertisingSet advertisingSet =
                            new AdvertisingSet(advertiserId, mBluetoothManager);
                        mAdvertisingSets.put(advertiserId, advertisingSet);
                        callback.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    }
                });
            }

            public void onAdvertisingSetStopped(int advertiserId) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onAdvertisingSetStopped(advertisingSet);
                        mAdvertisingSets.remove(advertiserId);
                        mCallbackWrappers.remove(callback);
                    }
                });
            }

            public void onAdvertisingEnabled(int advertiserId, boolean enabled, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onAdvertisingEnabled(advertisingSet, enabled, status);
                    }
                });
            }

            public void onAdvertisingDataSet(int advertiserId, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onAdvertisingDataSet(advertisingSet, status);
                    }
                });
            }

            public void onScanResponseDataSet(int advertiserId, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onScanResponseDataSet(advertisingSet, status);
                    }
                });
            }

            public void onAdvertisingParametersUpdated(int advertiserId, int txPower, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onAdvertisingParametersUpdated(advertisingSet, txPower, status);
                    }
                });
            }

            public void onPeriodicAdvertisingParametersUpdated(int advertiserId, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onPeriodicAdvertisingParametersUpdated(advertisingSet, status);
                    }
                });
            }

            public void onPeriodicAdvertisingDataSet(int advertiserId, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onPeriodicAdvertisingDataSet(advertisingSet, status);
                    }
                });
            }

            public void onPeriodicAdvertisingEnable(int advertiserId, boolean enable, int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        AdvertisingSet advertisingSet = mAdvertisingSets.get(advertiserId);
                        callback.onPeriodicAdvertisingEnable(advertisingSet, enable, status);
                    }
                });
            }
        };
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
