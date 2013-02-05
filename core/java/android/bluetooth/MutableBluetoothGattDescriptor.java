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

import java.util.UUID;

/**
 * Mutable variant of a Bluetooth Gatt Descriptor
 * @hide
 */
public class MutableBluetoothGattDescriptor extends BluetoothGattDescriptor {

    /**
     * Create a new BluetoothGattDescriptor.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid The UUID for this descriptor
     * @param permissions Permissions for this descriptor
     */
    public MutableBluetoothGattDescriptor(UUID uuid, int permissions) {
        super(null, uuid, permissions);
    }

    /**
     * Set the back-reference to the associated characteristic
     * @hide
     */
    /*package*/ void setCharacteristic(BluetoothGattCharacteristic characteristic) {
        mCharacteristic = characteristic;
    }
}
