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
     * delivered
     * through {@code callback.onAdvertisingEnabled()}.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     */
    public void enableAdvertising(boolean enable, int timeout) {
        try {
            gatt.enableAdvertisingSet(this.advertiserId, enable, timeout);
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
     */
    public void setAdvertisingData(AdvertiseData data) {
        try {
            gatt.setAdvertisingData(this.advertiserId, data);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Set/update scan response data. Make sure that data doesn't exceed the size limit for
     * specified AdvertisingSetParameters. This method returns immediately, the operation status
     * is delivered through {@code callback.onScanResponseDataSet()}.
     */
    public void setScanResponseData(AdvertiseData data) {
        try {
            gatt.setScanResponseData(this.advertiserId, data);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Update advertising parameters associated with this AdvertisingSet. Must be called when
     * advertising is not active. This method returns immediately, the operation status is delivered
     * through {@code callback.onAdvertisingParametersUpdated}.
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
     */
    public void setPeriodicAdvertisingData(AdvertiseData data) {
        try {
            gatt.setPeriodicAdvertisingData(this.advertiserId, data);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Used to enable/disable periodic advertising. This method returns immediately, the operation
     * status is delivered through {@code callback.onPeriodicAdvertisingEnable()}.
     */
    public void setPeriodicAdvertisingEnable(boolean enable) {
        try {
            gatt.setPeriodicAdvertisingEnable(this.advertiserId, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Returns advertiserId associated with thsi advertising set.
     *
     * @hide
     */
    public int getAdvertiserId(){
      return advertiserId;
    }
}