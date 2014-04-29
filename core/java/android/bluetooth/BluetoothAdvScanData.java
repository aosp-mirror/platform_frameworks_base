/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;


/**
 * This class provides the public APIs to set advertising and scan response data when BLE device
 * operates in peripheral mode. <br>
 * The exact format is defined in Bluetooth 4.0 specification, Volume 3, Part C, Section 11
 * @hide
 */
public final class BluetoothAdvScanData {

  /**
   * Available data types of {@link BluetoothAdvScanData}.
   */
  public static final int AD = 0;  // Advertising Data
  public static final int SCAN_RESPONSE = 1;  // Scan Response
  public static final int EIR = 2;  // Extended Inquiry Response

  private static final String TAG = "BluetoothAdvScanData";

  /**
   * Data type of BluetoothAdvScanData.
   */
  private final int mDataType;
  /**
   * Bluetooth Gatt Service.
   */
  private IBluetoothGatt mBluetoothGatt;

  /**
   * @param mBluetoothGatt
   * @param dataType
   */
  public BluetoothAdvScanData(IBluetoothGatt mBluetoothGatt, int dataType) {
    this.mBluetoothGatt = mBluetoothGatt;
    this.mDataType = dataType;
  }

  /**
   * @return advertising data type.
   */
  public int getDataType() {
    return mDataType;
  }

  /**
   * Set manufactureCode and manufactureData.
   * Returns true if manufacturer data is set, false if there is no enough room to set
   * manufacturer data or the data is already set.
   * @param manufacturerCode - unique identifier for the manufacturer
   * @param manufacturerData - data associated with the specific manufacturer.
   */
  public boolean setManufacturerData(int manufacturerCode, byte[] manufacturerData) {
    if (mDataType != AD) return false;
    try {
      return mBluetoothGatt.setAdvManufacturerCodeAndData(manufacturerCode, manufacturerData);
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to set manufacturer id and data.", e);
      return false;
    }
  }

  /**
   * Set service data.  Note the service data can only be set when the data type is {@code AD};
   * @param serviceData
   */
  public boolean setServiceData(byte[] serviceData) {

    if (mDataType != AD) return false;
    if (serviceData == null) return false;
    try {
      return mBluetoothGatt.setAdvServiceData(serviceData);
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to set service data.", e);
      return false;
    }
  }

  /**
   * Returns an immutable list of service uuids that will be advertised.
   */
  public List<ParcelUuid> getServiceUuids() {
    try {
      return Collections.unmodifiableList(mBluetoothGatt.getAdvServiceUuids());
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to get service uuids.", e);
      return null;
    }
  }

  /**
   * Returns manufacturer data.
   */
  public byte[] getManufacturerData() {
    if (mBluetoothGatt == null) return null;
    try {
      return mBluetoothGatt.getAdvManufacturerData();
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to get manufacturer data.", e);
      return null;
    }
  }

  /**
   * Returns service data.
   */
  public byte[] getServiceData() {
    if (mBluetoothGatt == null) return null;
    try {
      return mBluetoothGatt.getAdvServiceData();
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to get service data.", e);
      return null;
    }
  }

  /**
   * Remove manufacturer data based on given manufacturer code.
   * @param manufacturerCode
   */
  public void removeManufacturerCodeAndData(int manufacturerCode) {
    if (mBluetoothGatt != null) {
      try {
        mBluetoothGatt.removeAdvManufacturerCodeAndData(manufacturerCode);
      } catch (RemoteException e) {
        Log.e(TAG, "Unable to remove manufacturer : " + manufacturerCode, e);
      }
    }
  }
}
