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

package android.bluetooth;

/**
 * This abstract class is used to implement {@link BluetoothGattServer} callbacks.
 */
public abstract class BluetoothGattServerCallback {

    /**
     * Callback indicating when a remote device has been connected or disconnected.
     *
     * @param device Remote device that has been connected or disconnected.
     * @param status Status of the connect or disconnect operation.
     * @param newState Returns the new connection state. Can be one of {@link
     * BluetoothProfile#STATE_DISCONNECTED} or {@link BluetoothProfile#STATE_CONNECTED}
     */
    public void onConnectionStateChange(BluetoothDevice device, int status,
            int newState) {
    }

    /**
     * Indicates whether a local service has been added successfully.
     *
     * @param status Returns {@link BluetoothGatt#GATT_SUCCESS} if the service was added
     * successfully.
     * @param service The service that has been added
     */
    public void onServiceAdded(int status, BluetoothGattService service) {
    }

    /**
     * A remote client has requested to read a local characteristic.
     *
     * <p>An application must call {@link BluetoothGattServer#sendResponse}
     * to complete the request.
     *
     * @param device The remote device that has requested the read operation
     * @param requestId The Id of the request
     * @param offset Offset into the value of the characteristic
     * @param characteristic Characteristic to be read
     */
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
            int offset, BluetoothGattCharacteristic characteristic) {
    }

    /**
     * A remote client has requested to write to a local characteristic.
     *
     * <p>An application must call {@link BluetoothGattServer#sendResponse}
     * to complete the request.
     *
     * @param device The remote device that has requested the write operation
     * @param requestId The Id of the request
     * @param characteristic Characteristic to be written to.
     * @param preparedWrite true, if this write operation should be queued for later execution.
     * @param responseNeeded true, if the remote device requires a response
     * @param offset The offset given for the value
     * @param value The value the client wants to assign to the characteristic
     */
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite, boolean responseNeeded,
            int offset, byte[] value) {
    }

    /**
     * A remote client has requested to read a local descriptor.
     *
     * <p>An application must call {@link BluetoothGattServer#sendResponse}
     * to complete the request.
     *
     * @param device The remote device that has requested the read operation
     * @param requestId The Id of the request
     * @param offset Offset into the value of the characteristic
     * @param descriptor Descriptor to be read
     */
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
            int offset, BluetoothGattDescriptor descriptor) {
    }

    /**
     * A remote client has requested to write to a local descriptor.
     *
     * <p>An application must call {@link BluetoothGattServer#sendResponse}
     * to complete the request.
     *
     * @param device The remote device that has requested the write operation
     * @param requestId The Id of the request
     * @param descriptor Descriptor to be written to.
     * @param preparedWrite true, if this write operation should be queued for later execution.
     * @param responseNeeded true, if the remote device requires a response
     * @param offset The offset given for the value
     * @param value The value the client wants to assign to the descriptor
     */
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
            BluetoothGattDescriptor descriptor,
            boolean preparedWrite, boolean responseNeeded,
            int offset, byte[] value) {
    }

    /**
     * Execute all pending write operations for this device.
     *
     * <p>An application must call {@link BluetoothGattServer#sendResponse}
     * to complete the request.
     *
     * @param device The remote device that has requested the write operations
     * @param requestId The Id of the request
     * @param execute Whether the pending writes should be executed (true) or cancelled (false)
     */
    public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
    }

    /**
     * Callback invoked when a notification or indication has been sent to
     * a remote device.
     *
     * <p>When multiple notifications are to be sent, an application must
     * wait for this callback to be received before sending additional
     * notifications.
     *
     * @param device The remote device the notification has been sent to
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the operation was successful
     */
    public void onNotificationSent(BluetoothDevice device, int status) {
    }

    /**
     * Callback indicating the MTU for a given device connection has changed.
     *
     * <p>This callback will be invoked if a remote client has requested to change
     * the MTU for a given connection.
     *
     * @param device The remote device that requested the MTU change
     * @param mtu The new MTU size
     */
    public void onMtuChanged(BluetoothDevice device, int mtu) {
    }

    /**
     * Callback triggered as result of {@link BluetoothGattServer#setPreferredPhy}, or as a result
     * of remote device changing the PHY.
     *
     * @param device The remote device
     * @param txPhy the transmitter PHY in use. One of {@link BluetoothDevice#PHY_LE_1M}, {@link
     * BluetoothDevice#PHY_LE_2M}, and {@link BluetoothDevice#PHY_LE_CODED}
     * @param rxPhy the receiver PHY in use. One of {@link BluetoothDevice#PHY_LE_1M}, {@link
     * BluetoothDevice#PHY_LE_2M}, and {@link BluetoothDevice#PHY_LE_CODED}
     * @param status Status of the PHY update operation. {@link BluetoothGatt#GATT_SUCCESS} if the
     * operation succeeds.
     */
    public void onPhyUpdate(BluetoothDevice device, int txPhy, int rxPhy, int status) {
    }

    /**
     * Callback triggered as result of {@link BluetoothGattServer#readPhy}
     *
     * @param device The remote device that requested the PHY read
     * @param txPhy the transmitter PHY in use. One of {@link BluetoothDevice#PHY_LE_1M}, {@link
     * BluetoothDevice#PHY_LE_2M}, and {@link BluetoothDevice#PHY_LE_CODED}
     * @param rxPhy the receiver PHY in use. One of {@link BluetoothDevice#PHY_LE_1M}, {@link
     * BluetoothDevice#PHY_LE_2M}, and {@link BluetoothDevice#PHY_LE_CODED}
     * @param status Status of the PHY read operation. {@link BluetoothGatt#GATT_SUCCESS} if the
     * operation succeeds.
     */
    public void onPhyRead(BluetoothDevice device, int txPhy, int rxPhy, int status) {
    }

    /**
     * Callback indicating the connection parameters were updated.
     *
     * @param device The remote device involved
     * @param interval Connection interval used on this connection, 1.25ms unit. Valid range is from
     * 6 (7.5ms) to 3200 (4000ms).
     * @param latency Slave latency for the connection in number of connection events. Valid range
     * is from 0 to 499
     * @param timeout Supervision timeout for this connection, in 10ms unit. Valid range is from 10
     * (0.1s) to 3200 (32s)
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the connection has been updated
     * successfully
     * @hide
     */
    public void onConnectionUpdated(BluetoothDevice device, int interval, int latency, int timeout,
            int status) {
    }

}
