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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a Bluetooth Gatt Service
 * @hide
 */
public class MutableBluetoothGattService extends BluetoothGattService {

    /**
     * Create a new MutableBluetoothGattService.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid The UUID for this service
     * @param serviceType The type of this service (primary/secondary)
     */
    public MutableBluetoothGattService(UUID uuid, int serviceType) {
        super(uuid, serviceType);
    }

    /**
     * Add an included service to this service.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param service The service to be added
     * @return true, if the included service was added to the service
     */
    public boolean addService(BluetoothGattService service) {
        mIncludedServices.add(service);
        return true;
    }

    /**
     * Add a characteristic to this service.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param characteristic The characteristics to be added
     * @return true, if the characteristic was added to the service
     */
    public boolean addCharacteristic(MutableBluetoothGattCharacteristic characteristic) {
        mCharacteristics.add(characteristic);
        characteristic.setService(this);
        return true;
    }

    /**
     * Force the instance ID.
     * This is needed for conformance testing only.
     * @hide
     */
    public void setInstanceId(int instanceId) {
        mInstanceId = instanceId;
    }

    /**
     * Force the number of handles to reserve for this service.
     * This is needed for conformance testing only.
     * @hide
     */
    public void setHandles(int handles) {
        mHandles = handles;
    }
}
