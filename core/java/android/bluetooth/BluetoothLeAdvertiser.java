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

package android.bluetooth;

import android.bluetooth.BluetoothLeAdvertiseScanData.AdvertisementData;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides a way to perform Bluetooth LE advertise operations, such as start and stop
 * advertising. An advertiser can broadcast up to 31 bytes of advertisement data represented by
 * {@link BluetoothLeAdvertiseScanData.AdvertisementData}.
 * <p>
 * To get an instance of {@link BluetoothLeAdvertiser}, call the
 * {@link BluetoothAdapter#getBluetoothLeAdvertiser()} method.
 * <p>
 * Note most of the methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see BluetoothLeAdvertiseScanData.AdvertisementData
 */
public class BluetoothLeAdvertiser {

    private static final String TAG = "BluetoothLeAdvertiser";

    /**
     * The {@link Settings} provide a way to adjust advertising preferences for each individual
     * advertisement. Use {@link Settings.Builder} to create a {@link Settings} instance.
     */
    public static final class Settings implements Parcelable {
        /**
         * Perform Bluetooth LE advertising in low power mode. This is the default and preferred
         * advertising mode as it consumes the least power.
         */
        public static final int ADVERTISE_MODE_LOW_POWER = 0;
        /**
         * Perform Bluetooth LE advertising in balanced power mode. This is balanced between
         * advertising frequency and power consumption.
         */
        public static final int ADVERTISE_MODE_BALANCED = 1;
        /**
         * Perform Bluetooth LE advertising in low latency, high power mode. This has the highest
         * power consumption and should not be used for background continuous advertising.
         */
        public static final int ADVERTISE_MODE_LOW_LATENCY = 2;

        /**
         * Advertise using the lowest transmission(tx) power level. An app can use low transmission
         * power to restrict the visibility range of its advertising packet.
         */
        public static final int ADVERTISE_TX_POWER_ULTRA_LOW = 0;
        /**
         * Advertise using low tx power level.
         */
        public static final int ADVERTISE_TX_POWER_LOW = 1;
        /**
         * Advertise using medium tx power level.
         */
        public static final int ADVERTISE_TX_POWER_MEDIUM = 2;
        /**
         * Advertise using high tx power level. This is corresponding to largest visibility range of
         * the advertising packet.
         */
        public static final int ADVERTISE_TX_POWER_HIGH = 3;

        /**
         * Non-connectable undirected advertising event, as defined in Bluetooth Specification V4.0
         * vol6, part B, section 4.4.2 - Advertising state.
         */
        public static final int ADVERTISE_TYPE_NON_CONNECTABLE = 0;
        /**
         * Scannable undirected advertise type, as defined in same spec mentioned above. This event
         * type allows a scanner to send a scan request asking additional information about the
         * advertiser.
         */
        public static final int ADVERTISE_TYPE_SCANNABLE = 1;
        /**
         * Connectable undirected advertising type, as defined in same spec mentioned above. This
         * event type allows a scanner to send scan request asking additional information about the
         * advertiser. It also allows an initiator to send a connect request for connection.
         */
        public static final int ADVERTISE_TYPE_CONNECTABLE = 2;

        private final int mAdvertiseMode;
        private final int mAdvertiseTxPowerLevel;
        private final int mAdvertiseEventType;

        private Settings(int advertiseMode, int advertiseTxPowerLevel,
                int advertiseEventType) {
            mAdvertiseMode = advertiseMode;
            mAdvertiseTxPowerLevel = advertiseTxPowerLevel;
            mAdvertiseEventType = advertiseEventType;
        }

        private Settings(Parcel in) {
            mAdvertiseMode = in.readInt();
            mAdvertiseTxPowerLevel = in.readInt();
            mAdvertiseEventType = in.readInt();
        }

        /**
         * Creates a {@link Builder} to construct a {@link Settings} object.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        /**
         * Returns the advertise mode.
         */
        public int getMode() {
            return mAdvertiseMode;
        }

        /**
         * Returns the tx power level for advertising.
         */
        public int getTxPowerLevel() {
            return mAdvertiseTxPowerLevel;
        }

        /**
         * Returns the advertise event type.
         */
        public int getType() {
            return mAdvertiseEventType;
        }

        @Override
        public String toString() {
            return "Settings [mAdvertiseMode=" + mAdvertiseMode + ", mAdvertiseTxPowerLevel="
                    + mAdvertiseTxPowerLevel + ", mAdvertiseEventType=" + mAdvertiseEventType + "]";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mAdvertiseMode);
            dest.writeInt(mAdvertiseTxPowerLevel);
            dest.writeInt(mAdvertiseEventType);
        }

        public static final Parcelable.Creator<Settings> CREATOR =
                new Creator<BluetoothLeAdvertiser.Settings>() {
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
         * Builder class for {@link BluetoothLeAdvertiser.Settings}. Caller should use
         * {@link Settings#newBuilder()} to get an instance of the builder.
         */
        public static final class Builder {
            private int mMode = ADVERTISE_MODE_LOW_POWER;
            private int mTxPowerLevel = ADVERTISE_TX_POWER_MEDIUM;
            private int mType = ADVERTISE_TYPE_NON_CONNECTABLE;

            // Private constructor, use Settings.newBuilder() get an instance of BUILDER.
            private Builder() {
            }

            /**
             * Set advertise mode to control the advertising power and latency.
             *
             * @param advertiseMode Bluetooth LE Advertising mode, can only be one of
             *            {@link Settings#ADVERTISE_MODE_LOW_POWER},
             *            {@link Settings#ADVERTISE_MODE_BALANCED}, or
             *            {@link Settings#ADVERTISE_MODE_LOW_LATENCY}.
             * @throws IllegalArgumentException If the advertiseMode is invalid.
             */
            public Builder advertiseMode(int advertiseMode) {
                if (advertiseMode < ADVERTISE_MODE_LOW_POWER
                        || advertiseMode > ADVERTISE_MODE_LOW_LATENCY) {
                    throw new IllegalArgumentException("unknown mode " + advertiseMode);
                }
                mMode = advertiseMode;
                return this;
            }

            /**
             * Set advertise tx power level to control the transmission power level for the
             * advertising.
             *
             * @param txPowerLevel Transmission power of Bluetooth LE Advertising, can only be one
             *            of {@link Settings#ADVERTISE_TX_POWER_ULTRA_LOW},
             *            {@link Settings#ADVERTISE_TX_POWER_LOW},
             *            {@link Settings#ADVERTISE_TX_POWER_MEDIUM} or
             *            {@link Settings#ADVERTISE_TX_POWER_HIGH}.
             * @throws IllegalArgumentException If the {@code txPowerLevel} is invalid.
             */
            public Builder txPowerLevel(int txPowerLevel) {
                if (txPowerLevel < ADVERTISE_TX_POWER_ULTRA_LOW
                        || txPowerLevel > ADVERTISE_TX_POWER_HIGH) {
                    throw new IllegalArgumentException("unknown tx power level " + txPowerLevel);
                }
                mTxPowerLevel = txPowerLevel;
                return this;
            }

            /**
             * Set advertise type to control the event type of advertising.
             *
             * @param type Bluetooth LE Advertising type, can be either
             *            {@link Settings#ADVERTISE_TYPE_NON_CONNECTABLE},
             *            {@link Settings#ADVERTISE_TYPE_SCANNABLE} or
             *            {@link Settings#ADVERTISE_TYPE_CONNECTABLE}.
             * @throws IllegalArgumentException If the {@code type} is invalid.
             */
            public Builder type(int type) {
                if (type < ADVERTISE_TYPE_NON_CONNECTABLE
                        || type > ADVERTISE_TYPE_CONNECTABLE) {
                    throw new IllegalArgumentException("unknown advertise type " + type);
                }
                mType = type;
                return this;
            }

            /**
             * Build the {@link Settings} object.
             */
            public Settings build() {
                return new Settings(mMode, mTxPowerLevel, mType);
            }
        }
    }

    /**
     * Callback of Bluetooth LE advertising, which is used to deliver operation status for start and
     * stop advertising.
     */
    public interface AdvertiseCallback {

        /**
         * The operation is success.
         *
         * @hide
         */
        public static final int SUCCESS = 0;
        /**
         * Fails to start advertising as the advertisement data contains services that are not added
         * to the local bluetooth Gatt server.
         */
        public static final int ADVERTISING_SERVICE_UNKNOWN = 1;
        /**
         * Fails to start advertising as system runs out of quota for advertisers.
         */
        public static final int TOO_MANY_ADVERTISERS = 2;

        /**
         * Fails to start advertising as the advertising is already started.
         */
        public static final int ADVERTISING_ALREADY_STARTED = 3;
        /**
         * Fails to stop advertising as the advertising is not started.
         */
        public static final int ADVERISING_NOT_STARTED = 4;

        /**
         * Operation fails due to bluetooth controller failure.
         */
        public static final int CONTROLLER_FAILURE = 5;

        /**
         * Callback when advertising operation succeeds.
         *
         * @param settingsInEffect The actual settings used for advertising, which may be different
         *            from what the app asks.
         */
        public void onSuccess(Settings settingsInEffect);

        /**
         * Callback when advertising operation fails.
         *
         * @param errorCode Error code for failures.
         */
        public void onFailure(int errorCode);
    }

    private final IBluetoothGatt mBluetoothGatt;
    private final Handler mHandler;
    private final Map<Settings, AdvertiseCallbackWrapper>
            mLeAdvertisers = new HashMap<Settings, AdvertiseCallbackWrapper>();

    // Package private constructor, use BluetoothAdapter.getLeAdvertiser() instead.
    BluetoothLeAdvertiser(IBluetoothGatt bluetoothGatt) {
        mBluetoothGatt = bluetoothGatt;
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Bluetooth GATT interface callbacks for advertising.
     */
    private static class AdvertiseCallbackWrapper extends IBluetoothGattCallback.Stub {
        private static final int LE_CALLBACK_TIMEOUT_MILLIS = 2000;
        private final AdvertiseCallback mAdvertiseCallback;
        private final AdvertisementData mAdvertisement;
        private final Settings mSettings;
        private final IBluetoothGatt mBluetoothGatt;

        // mLeHandle 0: not registered
        // -1: scan stopped
        // >0: registered and scan started
        private int mLeHandle;
        private boolean isAdvertising = false;

        public AdvertiseCallbackWrapper(AdvertiseCallback advertiseCallback,
                AdvertisementData advertiseData, Settings settings,
                IBluetoothGatt bluetoothGatt) {
            mAdvertiseCallback = advertiseCallback;
            mAdvertisement = advertiseData;
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
                    Log.e(TAG, "Callback reg wait interrupted: " + e);
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
                        mBluetoothGatt.startMultiAdvertising(mLeHandle, mAdvertisement, mSettings);
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
        public void onConfigureMTU(String address, int mtu, int status) {
            // no op
        }
    }

    /**
     * Start Bluetooth LE Advertising.
     *
     * @param settings {@link Settings} for Bluetooth LE advertising.
     * @param advertiseData {@link AdvertisementData} to be advertised.
     * @param callback {@link AdvertiseCallback} for advertising status.
     */
    public void startAdvertising(Settings settings,
            AdvertisementData advertiseData, final AdvertiseCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (mLeAdvertisers.containsKey(settings)) {
            postCallbackFailure(callback, AdvertiseCallback.ADVERTISING_ALREADY_STARTED);
            return;
        }
        AdvertiseCallbackWrapper wrapper = new AdvertiseCallbackWrapper(callback, advertiseData,
                settings,
                mBluetoothGatt);
        UUID uuid = UUID.randomUUID();
        try {
            mBluetoothGatt.registerClient(new ParcelUuid(uuid), wrapper);
            if (wrapper.advertiseStarted()) {
                mLeAdvertisers.put(settings, wrapper);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to stop advertising", e);
        }
    }

    /**
     * Stop Bluetooth LE advertising. Returns immediately, the operation status will be delivered
     * through the {@code callback}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param settings {@link Settings} used to start Bluetooth LE advertising.
     * @param callback {@link AdvertiseCallback} for delivering stopping advertising status.
     */
    public void stopAdvertising(final Settings settings, final AdvertiseCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        AdvertiseCallbackWrapper wrapper = mLeAdvertisers.get(settings);
        if (wrapper == null) {
            postCallbackFailure(callback, AdvertiseCallback.ADVERISING_NOT_STARTED);
            return;
        }
        try {
            mBluetoothGatt.stopMultiAdvertising(wrapper.mLeHandle);
            if (wrapper.advertiseStopped()) {
                mLeAdvertisers.remove(settings);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "failed to stop advertising", e);
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
