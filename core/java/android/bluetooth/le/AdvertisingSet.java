/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.le.IAdvertisingSetCallback;
import android.os.RemoteException;
import android.util.Log;

/**
 * This class provides a way to control single Bluetooth LE advertising instance.
 * <p>
 * To get an instance of {@link AdvertisingSet}, call the
 * {@link BluetoothLeAdvertiser#startAdvertisingSet} method.
 * <p>
 * <b>Note:</b> Most of the methods here require {@link android.Manifest.permission#BLUETOOTH_ADMIN}
 * permission.
 *
 * @see AdvertiseData
 */
public final class AdvertisingSet {
    private static final String TAG = "AdvertisingSet";

    private final IBluetoothGatt gatt;
    private int advertiserId;

    /* package */ AdvertisingSet(int advertiserId,
                                 IBluetoothManager bluetoothManager) {
        this.advertiserId = advertiserId;

        try {
          this.gatt = bluetoothManager.getBluetoothGatt();
        } catch (RemoteException e) {
          Log.e(TAG, "Failed to get Bluetooth gatt - ", e);
          throw new IllegalStateException("Failed to get Bluetooth");
        }
    }

    /* package */ void setAdvertiserId(int advertiserId) {
      this.advertiserId = advertiserId;
    }

    /**
     * Enables Advertising. This method returns immediately, the operation status is
     * delivered through {@code callback.onAdvertisingEnabled()}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param enable whether the advertising should be enabled (true), or disabled (false)
     * @param duration advertising duration, in 10ms unit. Valid range is from 1 (10ms) to
     *                     65535 (655,350 ms)
     * @param maxExtendedAdvertisingEvents maximum number of extended advertising events the
     *                     controller shall attempt to send prior to terminating the extended
     *                     advertising, even if the duration has not expired. Valid range is
     *                     from 1 to 255.
     */
    public void enableAdvertising(boolean enable, int duration,
            int maxExtendedAdvertisingEvents) {
        try {
            gatt.enableAdvertisingSet(this.advertiserId, enable, duration,
                                      maxExtendedAdvertisingEvents);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Set/update data being Advertised. Make sure that data doesn't exceed the size limit for
     * specified AdvertisingSetParameters. This method returns immediately, the operation status is
     * delivered through {@code callback.onAdvertisingDataSet()}.
     * <p>
     * Advertising data must be empty if non-legacy scannable advertising is used.
     *
     * @param advertiseData Advertisement data to be broadcasted. Size must not exceed
     *                     {@link BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the
     *                     advertisement is connectable, three bytes will be added for flags. If the
     *                     update takes place when the advertising set is enabled, the data can be
     *                     maximum 251 bytes long.
     */
    public void setAdvertisingData(AdvertiseData advertiseData) {
        try {
            gatt.setAdvertisingData(this.advertiserId, advertiseData);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Set/update scan response data. Make sure that data doesn't exceed the size limit for
     * specified AdvertisingSetParameters. This method returns immediately, the operation status
     * is delivered through {@code callback.onScanResponseDataSet()}.
     *
     * @param scanResponse Scan response associated with the advertisement data. Size must not
     *                     exceed {@link BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the
     *                     update takes place when the advertising set is enabled, the data can be
     *                     maximum 251 bytes long.
     */
    public void setScanResponseData(AdvertiseData scanResponse) {
        try {
            gatt.setScanResponseData(this.advertiserId, scanResponse);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Update advertising parameters associated with this AdvertisingSet. Must be called when
     * advertising is not active. This method returns immediately, the operation status is delivered
     * through {@code callback.onAdvertisingParametersUpdated}.
     *
     * @param parameters advertising set parameters.
     */
    public void setAdvertisingParameters(AdvertisingSetParameters parameters) {
        try {
            gatt.setAdvertisingParameters(this.advertiserId, parameters);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Update periodic advertising parameters associated with this set. Must be called when
     * periodic advertising is not enabled. This method returns immediately, the operation
     * status is delivered through {@code callback.onPeriodicAdvertisingParametersUpdated()}.
     */
    public void setPeriodicAdvertisingParameters(PeriodicAdvertisingParameters parameters) {
        try {
            gatt.setPeriodicAdvertisingParameters(this.advertiserId, parameters);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Used to set periodic advertising data, must be called after setPeriodicAdvertisingParameters,
     * or after advertising was started with periodic advertising data set. This method returns
     * immediately, the operation status is delivered through
     * {@code callback.onPeriodicAdvertisingDataSet()}.
     *
     * @param periodicData Periodic advertising data. Size must not exceed
     *                     {@link BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the
     *                     update takes place when the periodic advertising is enabled for this set,
     *                     the data can be maximum 251 bytes long.
     */
    public void setPeriodicAdvertisingData(AdvertiseData periodicData) {
        try {
            gatt.setPeriodicAdvertisingData(this.advertiserId, periodicData);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Used to enable/disable periodic advertising. This method returns immediately, the operation
     * status is delivered through {@code callback.onPeriodicAdvertisingEnable()}.
     *
     * @param enable whether the periodic advertising should be enabled (true), or disabled (false).
     */
    public void setPeriodicAdvertisingEnabled(boolean enable) {
        try {
            gatt.setPeriodicAdvertisingEnable(this.advertiserId, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Returns address associated with this advertising set.
     * This method is exposed only for Bluetooth PTS tests, no app or system service
     * should ever use it.
     *
     * This method requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} permission.
     * @hide
     */
    public void getOwnAddress(){
        try {
            gatt.getOwnAddress(this.advertiserId);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Returns advertiserId associated with this advertising set.
     *
     * @hide
     */
    public int getAdvertiserId(){
      return advertiserId;
    }
}