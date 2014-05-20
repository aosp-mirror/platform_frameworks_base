/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * This class provides methods to perform scan related operations for Bluetooth LE devices. An
 * application can scan for a particular type of BLE devices using {@link BluetoothLeScanFilter}. It
 * can also request different types of callbacks for delivering the result.
 * <p>
 * Use {@link BluetoothAdapter#getBluetoothLeScanner()} to get an instance of
 * {@link BluetoothLeScanner}.
 * <p>
 * Note most of the scan methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see BluetoothLeScanFilter
 */
public class BluetoothLeScanner {

    private static final String TAG = "BluetoothLeScanner";
    private static final boolean DBG = true;

    /**
     * Settings for Bluetooth LE scan.
     */
    public static final class Settings implements Parcelable {
        /**
         * Perform Bluetooth LE scan in low power mode. This is the default scan mode as it consumes
         * the least power.
         */
        public static final int SCAN_MODE_LOW_POWER = 0;
        /**
         * Perform Bluetooth LE scan in balanced power mode.
         */
        public static final int SCAN_MODE_BALANCED = 1;
        /**
         * Scan using highest duty cycle. It's recommended only using this mode when the application
         * is running in foreground.
         */
        public static final int SCAN_MODE_LOW_LATENCY = 2;

        /**
         * Callback each time when a bluetooth advertisement is found.
         */
        public static final int CALLBACK_TYPE_ON_UPDATE = 0;
        /**
         * Callback when a bluetooth advertisement is found for the first time.
         */
        public static final int CALLBACK_TYPE_ON_FOUND = 1;
        /**
         * Callback when a bluetooth advertisement is found for the first time, then lost.
         */
        public static final int CALLBACK_TYPE_ON_LOST = 2;

        /**
         * Full scan result which contains device mac address, rssi, advertising and scan response
         * and scan timestamp.
         */
        public static final int SCAN_RESULT_TYPE_FULL = 0;
        /**
         * Truncated scan result which contains device mac address, rssi and scan timestamp. Note
         * it's possible for an app to get more scan results that it asks if there are multiple apps
         * using this type. TODO: decide whether we could unhide this setting.
         *
         * @hide
         */
        public static final int SCAN_RESULT_TYPE_TRUNCATED = 1;

        // Bluetooth LE scan mode.
        private int mScanMode;

        // Bluetooth LE scan callback type
        private int mCallbackType;

        // Bluetooth LE scan result type
        private int mScanResultType;

        // Time of delay for reporting the scan result
        private long mReportDelayMicros;

        public int getScanMode() {
            return mScanMode;
        }

        public int getCallbackType() {
            return mCallbackType;
        }

        public int getScanResultType() {
            return mScanResultType;
        }

        /**
         * Returns report delay timestamp based on the device clock.
         */
        public long getReportDelayMicros() {
            return mReportDelayMicros;
        }

        /**
         * Creates a new {@link Builder} to build {@link Settings} object.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        private Settings(int scanMode, int callbackType, int scanResultType,
                long reportDelayMicros) {
            mScanMode = scanMode;
            mCallbackType = callbackType;
            mScanResultType = scanResultType;
            mReportDelayMicros = reportDelayMicros;
        }

        private Settings(Parcel in) {
            mScanMode = in.readInt();
            mCallbackType = in.readInt();
            mScanResultType = in.readInt();
            mReportDelayMicros = in.readLong();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mScanMode);
            dest.writeInt(mCallbackType);
            dest.writeInt(mScanResultType);
            dest.writeLong(mReportDelayMicros);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Parcelable.Creator<Settings> CREATOR = new Creator<Settings>() {
                @Override
            public Settings[] newArray(int size) {
                return new Settings[size];
            }

                @Override
            public Settings createFromParcel(Parcel in) {
                return new Settings(in);
            }
        };

        /**
         * Builder for {@link BluetoothLeScanner.Settings}.
         */
        public static class Builder {
            private int mScanMode = SCAN_MODE_LOW_POWER;
            private int mCallbackType = CALLBACK_TYPE_ON_UPDATE;
            private int mScanResultType = SCAN_RESULT_TYPE_FULL;
            private long mReportDelayMicros = 0;

            // Hidden constructor.
            private Builder() {
            }

            /**
             * Set scan mode for Bluetooth LE scan.
             *
             * @param scanMode The scan mode can be one of {@link Settings#SCAN_MODE_LOW_POWER},
             *            {@link Settings#SCAN_MODE_BALANCED} or
             *            {@link Settings#SCAN_MODE_LOW_LATENCY}.
             * @throws IllegalArgumentException If the {@code scanMode} is invalid.
             */
            public Builder scanMode(int scanMode) {
                if (scanMode < SCAN_MODE_LOW_POWER || scanMode > SCAN_MODE_LOW_LATENCY) {
                    throw new IllegalArgumentException("invalid scan mode " + scanMode);
                }
                mScanMode = scanMode;
                return this;
            }

            /**
             * Set callback type for Bluetooth LE scan.
             *
             * @param callbackType The callback type for the scan. Can be either one of
             *            {@link Settings#CALLBACK_TYPE_ON_UPDATE},
             *            {@link Settings#CALLBACK_TYPE_ON_FOUND} or
             *            {@link Settings#CALLBACK_TYPE_ON_LOST}.
             * @throws IllegalArgumentException If the {@code callbackType} is invalid.
             */
            public Builder callbackType(int callbackType) {
                if (callbackType < CALLBACK_TYPE_ON_UPDATE
                        || callbackType > CALLBACK_TYPE_ON_LOST) {
                    throw new IllegalArgumentException("invalid callback type - " + callbackType);
                }
                mCallbackType = callbackType;
                return this;
            }

            /**
             * Set scan result type for Bluetooth LE scan.
             *
             * @param scanResultType Type for scan result, could be either
             *            {@link Settings#SCAN_RESULT_TYPE_FULL} or
             *            {@link Settings#SCAN_RESULT_TYPE_TRUNCATED}.
             * @throws IllegalArgumentException If the {@code scanResultType} is invalid.
             * @hide
             */
            public Builder scanResultType(int scanResultType) {
                if (scanResultType < SCAN_RESULT_TYPE_FULL
                        || scanResultType > SCAN_RESULT_TYPE_TRUNCATED) {
                    throw new IllegalArgumentException(
                            "invalid scanResultType - " + scanResultType);
                }
                mScanResultType = scanResultType;
                return this;
            }

            /**
             * Set report delay timestamp for Bluetooth LE scan.
             */
            public Builder reportDelayMicros(long reportDelayMicros) {
                mReportDelayMicros = reportDelayMicros;
                return this;
            }

            /**
             * Build {@link Settings}.
             */
            public Settings build() {
                return new Settings(mScanMode, mCallbackType, mScanResultType, mReportDelayMicros);
            }
        }
    }

    /**
     * ScanResult for Bluetooth LE scan.
     */
    public static final class ScanResult implements Parcelable {
        // Remote bluetooth device.
        private BluetoothDevice mDevice;

        // Scan record, including advertising data and scan response data.
        private byte[] mScanRecord;

        // Received signal strength.
        private int mRssi;

        // Device timestamp when the result was last seen.
        private long mTimestampMicros;

        // Constructor of scan result.
        public ScanResult(BluetoothDevice device, byte[] scanRecord, int rssi, long timestampMicros) {
            mDevice = device;
            mScanRecord = scanRecord;
            mRssi = rssi;
            mTimestampMicros = timestampMicros;
        }

        private ScanResult(Parcel in) {
            readFromParcel(in);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            if (mDevice != null) {
                dest.writeInt(1);
                mDevice.writeToParcel(dest, flags);
            } else {
                dest.writeInt(0);
            }
            if (mScanRecord != null) {
                dest.writeInt(1);
                dest.writeByteArray(mScanRecord);
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(mRssi);
            dest.writeLong(mTimestampMicros);
        }

        private void readFromParcel(Parcel in) {
            if (in.readInt() == 1) {
                mDevice = BluetoothDevice.CREATOR.createFromParcel(in);
            }
            if (in.readInt() == 1) {
                mScanRecord = in.createByteArray();
            }
            mRssi = in.readInt();
            mTimestampMicros = in.readLong();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * Returns the remote bluetooth device identified by the bluetooth device address.
         */
        @Nullable
        public BluetoothDevice getDevice() {
            return mDevice;
        }

        @Nullable /**
                   * Returns the scan record, which can be a combination of advertisement and scan response.
                   */
        public byte[] getScanRecord() {
            return mScanRecord;
        }

        /**
         * Returns the received signal strength in dBm. The valid range is [-127, 127].
         */
        public int getRssi() {
            return mRssi;
        }

        /**
         * Returns timestamp since boot when the scan record was observed.
         */
        public long getTimestampMicros() {
            return mTimestampMicros;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDevice, mRssi, mScanRecord, mTimestampMicros);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            ScanResult other = (ScanResult) obj;
            return Objects.equals(mDevice, other.mDevice) && (mRssi == other.mRssi) &&
                    Objects.deepEquals(mScanRecord, other.mScanRecord)
                    && (mTimestampMicros == other.mTimestampMicros);
        }

        @Override
        public String toString() {
            return "ScanResult{" + "mDevice=" + mDevice + ", mScanRecord="
                    + Arrays.toString(mScanRecord) + ", mRssi=" + mRssi + ", mTimestampMicros="
                    + mTimestampMicros + '}';
        }

        public static final Parcelable.Creator<ScanResult> CREATOR = new Creator<ScanResult>() {
                @Override
            public ScanResult createFromParcel(Parcel source) {
                return new ScanResult(source);
            }

                @Override
            public ScanResult[] newArray(int size) {
                return new ScanResult[size];
            }
        };

    }

    /**
     * Callback of Bluetooth LE scans. The results of the scans will be delivered through the
     * callbacks.
     */
    public interface ScanCallback {
        /**
         * Callback when any BLE beacon is found.
         *
         * @param result A Bluetooth LE scan result.
         */
        public void onDeviceUpdate(ScanResult result);

        /**
         * Callback when the BLE beacon is found for the first time.
         *
         * @param result The Bluetooth LE scan result when the onFound event is triggered.
         */
        public void onDeviceFound(ScanResult result);

        /**
         * Callback when the BLE device was lost. Note a device has to be "found" before it's lost.
         *
         * @param device The Bluetooth device that is lost.
         */
        public void onDeviceLost(BluetoothDevice device);

        /**
         * Callback when batch results are delivered.
         *
         * @param results List of scan results that are previously scanned.
         */
        public void onBatchScanResults(List<ScanResult> results);

        /**
         * Fails to start scan as BLE scan with the same settings is already started by the app.
         */
        public static final int SCAN_ALREADY_STARTED = 1;
        /**
         * Fails to start scan as app cannot be registered.
         */
        public static final int APPLICATION_REGISTRATION_FAILED = 2;
        /**
         * Fails to start scan due to gatt service failure.
         */
        public static final int GATT_SERVICE_FAILURE = 3;
        /**
         * Fails to start scan due to controller failure.
         */
        public static final int CONTROLLER_FAILURE = 4;

        /**
         * Callback when scan failed.
         */
        public void onScanFailed(int errorCode);
    }

    private final IBluetoothGatt mBluetoothGatt;
    private final Handler mHandler;
    private final Map<Settings, BleScanCallbackWrapper> mLeScanClients;

    BluetoothLeScanner(IBluetoothGatt bluetoothGatt) {
        mBluetoothGatt = bluetoothGatt;
        mHandler = new Handler(Looper.getMainLooper());
        mLeScanClients = new HashMap<Settings, BleScanCallbackWrapper>();
    }

    /**
     * Bluetooth GATT interface callbacks
     */
    private static class BleScanCallbackWrapper extends IBluetoothGattCallback.Stub {
        private static final int REGISTRATION_CALLBACK_TIMEOUT_SECONDS = 5;

        private final ScanCallback mScanCallback;
        private final List<BluetoothLeScanFilter> mFilters;
        private Settings mSettings;
        private IBluetoothGatt mBluetoothGatt;

        // mLeHandle 0: not registered
        // -1: scan stopped
        // > 0: registered and scan started
        private int mLeHandle;

        public BleScanCallbackWrapper(IBluetoothGatt bluetoothGatt,
                List<BluetoothLeScanFilter> filters, Settings settings, ScanCallback scanCallback) {
            mBluetoothGatt = bluetoothGatt;
            mFilters = filters;
            mSettings = settings;
            mScanCallback = scanCallback;
            mLeHandle = 0;
        }

        public boolean scanStarted() {
            synchronized (this) {
                if (mLeHandle == -1) {
                    return false;
                }
                try {
                    wait(REGISTRATION_CALLBACK_TIMEOUT_SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Callback reg wait interrupted: " + e);
                }
            }
            return mLeHandle > 0;
        }

        public void stopLeScan() {
            synchronized (this) {
                if (mLeHandle <= 0) {
                    Log.e(TAG, "Error state, mLeHandle: " + mLeHandle);
                    return;
                }
                try {
                    mBluetoothGatt.stopScan(mLeHandle, false);
                    mBluetoothGatt.unregisterClient(mLeHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop scan and unregister" + e);
                }
                mLeHandle = -1;
                notifyAll();
            }
        }

        /**
         * Application interface registered - app is ready to go
         */
        @Override
        public void onClientRegistered(int status, int clientIf) {
            Log.d(TAG, "onClientRegistered() - status=" + status +
                    " clientIf=" + clientIf);

            synchronized (this) {
                if (mLeHandle == -1) {
                    if (DBG)
                        Log.d(TAG, "onClientRegistered LE scan canceled");
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mLeHandle = clientIf;
                    try {
                        mBluetoothGatt.startScanWithFilters(mLeHandle, false, mSettings, mFilters);
                    } catch (RemoteException e) {
                        Log.e(TAG, "fail to start le scan: " + e);
                        mLeHandle = -1;
                    }
                } else {
                    // registration failed
                    mLeHandle = -1;
                }
                notifyAll();
            }
        }

        @Override
        public void onClientConnectionState(int status, int clientIf,
                boolean connected, String address) {
            // no op
        }

        /**
         * Callback reporting an LE scan result.
         *
         * @hide
         */
        @Override
        public void onScanResult(String address, int rssi, byte[] advData) {
            if (DBG)
                Log.d(TAG, "onScanResult() - Device=" + address + " RSSI=" + rssi);

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mLeHandle <= 0)
                    return;
            }
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(
                    address);
            long scanMicros = TimeUnit.NANOSECONDS.toMicros(SystemClock.elapsedRealtimeNanos());
            ScanResult result = new ScanResult(device, advData, rssi,
                    scanMicros);
            mScanCallback.onDeviceUpdate(result);
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
            // no op
        }

        @Override
        public void onConfigureMTU(String address, int mtu, int status) {
            // no op
        }
    }

    /**
     * Scan Bluetooth LE scan. The scan results will be delivered through {@code callback}.
     *
     * @param filters {@link BluetoothLeScanFilter}s for finding exact BLE devices.
     * @param settings Settings for ble scan.
     * @param callback Callback when scan results are delivered.
     * @throws IllegalArgumentException If {@code settings} or {@code callback} is null.
     */
    public void startScan(List<BluetoothLeScanFilter> filters, Settings settings,
            final ScanCallback callback) {
        if (settings == null || callback == null) {
            throw new IllegalArgumentException("settings or callback is null");
        }
        synchronized (mLeScanClients) {
            if (mLeScanClients.get(settings) != null) {
                postCallbackError(callback, ScanCallback.SCAN_ALREADY_STARTED);
                return;
            }
            BleScanCallbackWrapper wrapper = new BleScanCallbackWrapper(mBluetoothGatt, filters,
                    settings, callback);
            try {
                UUID uuid = UUID.randomUUID();
                mBluetoothGatt.registerClient(new ParcelUuid(uuid), wrapper);
                if (wrapper.scanStarted()) {
                    mLeScanClients.put(settings, wrapper);
                } else {
                    postCallbackError(callback, ScanCallback.APPLICATION_REGISTRATION_FAILED);
                    return;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "GATT service exception when starting scan", e);
                postCallbackError(callback, ScanCallback.GATT_SERVICE_FAILURE);
            }
        }
    }

    private void postCallbackError(final ScanCallback callback, final int errorCode) {
        mHandler.post(new Runnable() {
                @Override
            public void run() {
                callback.onScanFailed(errorCode);
            }
        });
    }

    /**
     * Stop Bluetooth LE scan.
     *
     * @param settings The same settings as used in {@link #startScan}, which is used to identify
     *            the BLE scan.
     */
    public void stopScan(Settings settings) {
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.remove(settings);
            if (wrapper == null) {
                return;
            }
            wrapper.stopLeScan();
        }
    }

    /**
     * Returns available storage size for batch scan results. It's recommended not to use batch scan
     * if available storage size is small (less than 1k bytes, for instance).
     *
     * @hide TODO: unhide when batching is supported in stack.
     */
    public int getAvailableBatchStorageSizeBytes() {
        throw new UnsupportedOperationException("not impelemented");
    }

    /**
     * Poll scan results from bluetooth controller. This will return Bluetooth LE scan results
     * batched on bluetooth controller.
     *
     * @param callback Callback of the Bluetooth LE Scan, it has to be the same instance as the one
     *            used to start scan.
     * @param flush Whether to flush the batch scan buffer. Note the other batch scan clients will
     *            get batch scan callback if the batch scan buffer is flushed.
     * @return Batch Scan results.
     * @hide TODO: unhide when batching is supported in stack.
     */
    public List<ScanResult> getBatchScanResults(ScanCallback callback, boolean flush) {
        throw new UnsupportedOperationException("not impelemented");
    }

}
