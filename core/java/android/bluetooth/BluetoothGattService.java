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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a Bluetooth GATT Service
 *
 * <p> Gatt Service contains a collection of {@link BluetoothGattCharacteristic},
 * as well as referenced services.
 */
public class BluetoothGattService implements Parcelable {

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
     *
     * @hide
     */
    @UnsupportedAppUsage
    protected BluetoothDevice mDevice;

    /**
     * The UUID of this service.
     *
     * @hide
     */
    protected UUID mUuid;

    /**
     * Instance ID for this service.
     *
     * @hide
     */
    protected int mInstanceId;

    /**
     * Handle counter override (for conformance testing).
     *
     * @hide
     */
    protected int mHandles = 0;

    /**
     * Service type (Primary/Secondary).
     *
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
     * Whether the service uuid should be advertised.
     */
    private boolean mAdvertisePreferred;

    /**
     * Create a new BluetoothGattService.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param uuid The UUID for this service
     * @param serviceType The type of this service,
     * {@link BluetoothGattService#SERVICE_TYPE_PRIMARY}
     * or {@link BluetoothGattService#SERVICE_TYPE_SECONDARY}
     */
    public BluetoothGattService(UUID uuid, int serviceType) {
        mDevice = null;
        mUuid = uuid;
        mInstanceId = 0;
        mServiceType = serviceType;
        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
        mIncludedServices = new ArrayList<BluetoothGattService>();
    }

    /**
     * Create a new BluetoothGattService
     *
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
     * Create a new BluetoothGattService
     *
     * @hide
     */
    public BluetoothGattService(UUID uuid, int instanceId, int serviceType) {
        mDevice = null;
        mUuid = uuid;
        mInstanceId = instanceId;
        mServiceType = serviceType;
        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
        mIncludedServices = new ArrayList<BluetoothGattService>();
    }

    /**
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(new ParcelUuid(mUuid), 0);
        out.writeInt(mInstanceId);
        out.writeInt(mServiceType);
        out.writeTypedList(mCharacteristics);

        ArrayList<BluetoothGattIncludedService> includedServices =
                new ArrayList<BluetoothGattIncludedService>(mIncludedServices.size());
        for (BluetoothGattService s : mIncludedServices) {
            includedServices.add(new BluetoothGattIncludedService(s.getUuid(),
                    s.getInstanceId(), s.getType()));
        }
        out.writeTypedList(includedServices);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothGattService> CREATOR =
            new Parcelable.Creator<BluetoothGattService>() {
        public BluetoothGattService createFromParcel(Parcel in) {
            return new BluetoothGattService(in);
        }

        public BluetoothGattService[] newArray(int size) {
            return new BluetoothGattService[size];
        }
    };

    private BluetoothGattService(Parcel in) {
        mUuid = ((ParcelUuid) in.readParcelable(null)).getUuid();
        mInstanceId = in.readInt();
        mServiceType = in.readInt();

        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();

        ArrayList<BluetoothGattCharacteristic> chrcs =
                in.createTypedArrayList(BluetoothGattCharacteristic.CREATOR);
        if (chrcs != null) {
            for (BluetoothGattCharacteristic chrc : chrcs) {
                chrc.setService(this);
                mCharacteristics.add(chrc);
            }
        }

        mIncludedServices = new ArrayList<BluetoothGattService>();

        ArrayList<BluetoothGattIncludedService> inclSvcs =
                in.createTypedArrayList(BluetoothGattIncludedService.CREATOR);
        if (chrcs != null) {
            for (BluetoothGattIncludedService isvc : inclSvcs) {
                mIncludedServices.add(new BluetoothGattService(null, isvc.getUuid(),
                        isvc.getInstanceId(), isvc.getType()));
            }
        }
    }

    /**
     * Returns the device associated with this service.
     *
     * @hide
     */
    /*package*/ BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Returns the device associated with this service.
     *
     * @hide
     */
    /*package*/ void setDevice(BluetoothDevice device) {
        mDevice = device;
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
    public boolean addCharacteristic(BluetoothGattCharacteristic characteristic) {
        mCharacteristics.add(characteristic);
        characteristic.setService(this);
        return true;
    }

    /**
     * Get characteristic by UUID and instanceId.
     *
     * @hide
     */
    /*package*/ BluetoothGattCharacteristic getCharacteristic(UUID uuid, int instanceId) {
        for (BluetoothGattCharacteristic characteristic : mCharacteristics) {
            if (uuid.equals(characteristic.getUuid())
                    && characteristic.getInstanceId() == instanceId) {
                return characteristic;
            }
        }
        return null;
    }

    /**
     * Force the instance ID.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setInstanceId(int instanceId) {
        mInstanceId = instanceId;
    }

    /**
     * Get the handle count override (conformance testing.
     *
     * @hide
     */
    /*package*/ int getHandles() {
        return mHandles;
    }

    /**
     * Force the number of handles to reserve for this service.
     * This is needed for conformance testing only.
     *
     * @hide
     */
    public void setHandles(int handles) {
        mHandles = handles;
    }

    /**
     * Add an included service to the internal map.
     *
     * @hide
     */
    public void addIncludedService(BluetoothGattService includedService) {
        mIncludedServices.add(includedService);
    }

    /**
     * Returns the UUID of this service
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
     * @return Instance ID of this service
     */
    public int getInstanceId() {
        return mInstanceId;
    }

    /**
     * Get the type of this service (primary/secondary)
     */
    public int getType() {
        return mServiceType;
    }

    /**
     * Get the list of included GATT services for this service.
     *
     * @return List of included services or empty list if no included services were discovered.
     */
    public List<BluetoothGattService> getIncludedServices() {
        return mIncludedServices;
    }

    /**
     * Returns a list of characteristics included in this service.
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
     * @return GATT characteristic object or null if no characteristic with the given UUID was
     * found.
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        for (BluetoothGattCharacteristic characteristic : mCharacteristics) {
            if (uuid.equals(characteristic.getUuid())) {
                return characteristic;
            }
        }
        return null;
    }

    /**
     * Returns whether the uuid of the service should be advertised.
     *
     * @hide
     */
    public boolean isAdvertisePreferred() {
        return mAdvertisePreferred;
    }

    /**
     * Set whether the service uuid should be advertised.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setAdvertisePreferred(boolean advertisePreferred) {
        mAdvertisePreferred = advertisePreferred;
    }
}
