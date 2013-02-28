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

import android.bluetooth.BluetoothDevice;

import android.util.Log;

/**
 * This abstract class is used to implement {@link BluetoothGatt} callbacks.
 * @hide
 */
public abstract class BluetoothGattCallback {
    /**
     * Callback to inform change in registration state of the  application.
     *
     * @param status Returns {@link BluetoothGatt#GATT_SUCCESS} if the application
     *               was successfully registered.
     */
    public void onAppRegistered(int status) {
    }

    /**
     * Callback reporting an LE device found during a device scan initiated
     * by the {@link BluetoothGatt#startScan} function.
     *
     * @param device Identifies the remote device
     * @param rssi The RSSI value for the remote device as reported by the
     *             Bluetooth hardware. 0 if no RSSI value is available.
     * @param scanRecord The content of the advertisement record offered by
     *                   the remote device.
     */
    public void onScanResult(BluetoothDevice device, int rssi, byte[] scanRecord) {
    }

    /**
     * Callback indicating when a remote device has been connected or disconnected.
     *
     * @param device Remote device that has been connected or disconnected.
     * @param status Status of the connect or disconnect operation.
     * @param newState Returns the new connection state. Can be one of
     *                  {@link BluetoothProfile#STATE_DISCONNECTED} or
     *                  {@link BluetoothProfile#STATE_CONNECTED}
     */
    public void onConnectionStateChange(BluetoothDevice device, int status,
                                        int newState) {
    }

    /**
     * Callback invoked when the list of remote services, characteristics and
     * descriptors for the remote device have been updated.
     *
     * @param device Remote device
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the remote device
     *               has been explored successfully.
     */
    public void onServicesDiscovered(BluetoothDevice device, int status) {
    }

    /**
     * Callback reporting the result of a characteristic read operation.
     *
     * @param characteristic Characteristic that was read from the associated
     *                       remote device.
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the read operation
     *               was completed successfully.
     */
    public void onCharacteristicRead(BluetoothGattCharacteristic characteristic,
                                     int status) {
    }

    /**
     * Callback indicating the result of a characteristic write operation.
     *
     * <p>If this callback is invoked while a reliable write transaction is
     * in progress, the value of the characteristic represents the value
     * reported by the remote device. An application should compare this
     * value to the desired value to be written. If the values don't match,
     * the application must abort the reliable write transaction.
     *
     * @param characteristic Characteristic that was written to the associated
     *                       remote device.
     * @param status The result of the write operation
     */
    public void onCharacteristicWrite(BluetoothGattCharacteristic characteristic,
                               int status) {
    }

    /**
     * Callback triggered as a result of a remote characteristic notification.
     *
     * @param characteristic Characteristic that has been updated as a result
     *                       of a remote notification event.
     */
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
    }

    /**
     * Callback reporting the result of a descriptor read operation.
     *
     * @param descriptor Descriptor that was read from the associated
     *                       remote device.
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the read operation
     *               was completed successfully
     */
    public void onDescriptorRead(BluetoothGattDescriptor descriptor,
                                     int status) {
    }

    /**
     * Callback indicating the result of a descriptor write operation.
     *
     * @param descriptor Descriptor that was writte to the associated
     *                       remote device.
     * @param status The result of the write operation
     */
    public void onDescriptorWrite(BluetoothGattDescriptor descriptor,
                               int status) {
    }

    /**
     * Callback invoked when a reliable write transaction has been completed.
     *
     * @param device Remote device
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the reliable write
     *               transaction was executed successfully
     */
    public void onReliableWriteCompleted(BluetoothDevice device, int status) {
    }

    /**
     * Callback reporting the RSSI for a remote device connection.
     *
     * This callback is triggered in response to the
     * {@link BluetoothGatt#readRemoteRssi} function.
     *
     * @param device Identifies the remote device
     * @param rssi The RSSI value for the remote device
     * @param status 0 if the RSSI was read successfully
     */
    public void onReadRemoteRssi(BluetoothDevice device, int rssi, int status) {
    }
}
