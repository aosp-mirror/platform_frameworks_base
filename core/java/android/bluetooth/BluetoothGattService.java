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
public class BluetoothGattService {

    /**
     * Primary service
     */
    public static final int SERVICE_TYPE_PRIMARY = 0;

    /**
     * Secondary service (included by primary services)
     */
    public static final int SERVICE_TYPE_SECONDARY = 1;


    /**
     * The remote device his service is associated with.
     * This applies to client applications only.
     * @hide
     */
    protected BluetoothDevice mDevice;

    /**
     * The UUID of this service.
     * @hide
     */
    protected UUID mUuid;

    /**
     * Instance ID for this service.
     * @hide
     */
    protected int mInstanceId;

    /**
     * Handle counter override (for conformance testing).
     * @hide
     */
    protected int mHandles = 0;

    /**
     * Service type (Primary/Secondary).
     * @hide
     */
    protected int mServiceType;

    /**
     * List of characteristics included in this service.
     */
    protected List<BluetoothGattCharacteristic> mCharacteristics;

    /**
     * List of included services for this service.
     */
    protected List<BluetoothGattService> mIncludedServices;

    /**
     * Create a new BluetoothGattService.
     * @hide
     */
    /*package*/ BluetoothGattService(UUID uuid, int serviceType) {
        mDevice = null;
        mUuid = uuid;
        mInstanceId = 0;
        mServiceType = serviceType;
        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
        mIncludedServices = new ArrayList<BluetoothGattService>();
    }

    /**
     * Create a new BluetoothGattService
     * @hide
     */
    /*package*/ BluetoothGattService(BluetoothDevice device, UUID uuid,
                                     int instanceId, int serviceType) {
        mDevice = device;
        mUuid = uuid;
        mInstanceId = instanceId;
        mServiceType = serviceType;
        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
        mIncludedServices = new ArrayList<BluetoothGattService>();
    }

    /**
     * Returns the device associated with this service.
     * @hide
     */
    /*package*/ BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Add a characteristic to this service.
     * @hide
     */
    /*package*/ void addCharacteristic(BluetoothGattCharacteristic characteristic) {
        mCharacteristics.add(characteristic);
    }

    /**
     * Get characteristic by UUID and instanceId.
     * @hide
     */
    /*package*/ BluetoothGattCharacteristic getCharacteristic(UUID uuid, int instanceId) {
        for(BluetoothGattCharacteristic characteristic : mCharacteristics) {
            if (uuid.equals(characteristic.getUuid()) &&
                    mInstanceId == instanceId)
                return characteristic;
        }
        return null;
    }

    /**
     * Get the handle count override (conformance testing.
     * @hide
     */
    /*package*/ int getHandles() {
        return mHandles;
    }

    /**
     * Add an included service to the internal map.
     * @hide
     */
    /*package*/ void addIncludedService(BluetoothGattService includedService) {
        mIncludedServices.add(includedService);
    }

    /**
     * Returns the UUID of this service
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return UUID of this service
     */
    public UUID getUuid() {
        return mUuid;
    }

    /**
     * Returns the instance ID for this service
     *
     * <p>If a remote device offers multiple services with the same UUID
     * (ex. multiple battery services for different batteries), the instance
     * ID is used to distuinguish services.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return Instance ID of this service
     */
    public int getInstanceId() {
        return mInstanceId;
    }

    /**
     * Get the type of this service (primary/secondary)
     * @hide
     */
    public int getType() {
        return mServiceType;
    }

    /**
     * Get the list of included Gatt services for this service.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return List of included services or empty list if no included services
     *         were discovered.
     */
    public List<BluetoothGattService> getIncludedServices() {
        return mIncludedServices;
    }

    /**
     * Returns a list of characteristics included in this service.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return Characteristics included in this service
     */
    public List<BluetoothGattCharacteristic> getCharacteristics() {
        return mCharacteristics;
    }

    /**
     * Returns a characteristic with a given UUID out of the list of
     * characteristics offered by this service.
     *
     * <p>This is a convenience function to allow access to a given characteristic
     * without enumerating over the list returned by {@link #getCharacteristics}
     * manually.
     *
     * <p>If a remote service offers multiple characteristics with the same
     * UUID, the first instance of a characteristic with the given UUID
     * is returned.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return Gatt characteristic object or null if no characteristic with the
     *         given UUID was found.
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        for(BluetoothGattCharacteristic characteristic : mCharacteristics) {
            if (uuid.equals(characteristic.getUuid()))
                return characteristic;
        }
        return null;
    }
}
