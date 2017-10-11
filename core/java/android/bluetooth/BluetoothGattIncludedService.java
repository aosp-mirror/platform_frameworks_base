/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.UUID;

/**
 * Represents a Bluetooth GATT Included Service
 *
 * @hide
 */
public class BluetoothGattIncludedService implements Parcelable {

    /**
     * The UUID of this service.
     */
    protected UUID mUuid;

    /**
     * Instance ID for this service.
     */
    protected int mInstanceId;

    /**
     * Service type (Primary/Secondary).
     */
    protected int mServiceType;

    /**
     * Create a new BluetoothGattIncludedService
     */
    public BluetoothGattIncludedService(UUID uuid, int instanceId, int serviceType) {
        mUuid = uuid;
        mInstanceId = instanceId;
        mServiceType = serviceType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(new ParcelUuid(mUuid), 0);
        out.writeInt(mInstanceId);
        out.writeInt(mServiceType);
    }

    public static final Parcelable.Creator<BluetoothGattIncludedService> CREATOR =
            new Parcelable.Creator<BluetoothGattIncludedService>() {
        public BluetoothGattIncludedService createFromParcel(Parcel in) {
            return new BluetoothGattIncludedService(in);
        }

        public BluetoothGattIncludedService[] newArray(int size) {
            return new BluetoothGattIncludedService[size];
        }
    };

    private BluetoothGattIncludedService(Parcel in) {
        mUuid = ((ParcelUuid) in.readParcelable(null)).getUuid();
        mInstanceId = in.readInt();
        mServiceType = in.readInt();
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
}
