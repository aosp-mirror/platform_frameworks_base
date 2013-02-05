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

import java.util.ArrayList;
import java.util.IllegalFormatConversionException;
import java.util.List;
import java.util.UUID;

/**
 * Mutable variant of a Bluetooth Gatt Characteristic
 * @hide
 */
public class MutableBluetoothGattCharacteristic extends BluetoothGattCharacteristic {

    /**
     * Create a new MutableBluetoothGattCharacteristic.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid The UUID for this characteristic
     * @param properties Properties of this characteristic
     * @param permissions Permissions for this characteristic
     */
    public MutableBluetoothGattCharacteristic(UUID uuid, int properties, int permissions) {
        super(null, uuid, 0, properties, permissions);
    }

    /**
     * Adds a descriptor to this characteristic.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param descriptor Descriptor to be added to this characteristic.
     */
    public void addDescriptor(MutableBluetoothGattDescriptor descriptor) {
        mDescriptors.add(descriptor);
        descriptor.setCharacteristic(this);
    }

    /**
     * Set the desired key size.
     * @hide
     */
    public void setKeySize(int keySize) {
        mKeySize = keySize;
    }

    /**
     * Sets the service associated with this device.
     * @hide
     */
    /*package*/ void setService(BluetoothGattService service) {
        mService = service;
    }
}
