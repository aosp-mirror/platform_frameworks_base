/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.sensor;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.hardware.Sensor;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * Representation of a sensor on a remote device, capable of sending events, such as an
 * accelerometer or a gyroscope.
 *
 * <p>A virtual sensor device is registered with the sensor framework as a runtime sensor.
 *
 * @hide
 */
@SystemApi
public final class VirtualSensor implements Parcelable {
    private final int mHandle;
    private final int mType;
    private final String mName;
    private final IVirtualDevice mVirtualDevice;
    private final IBinder mToken;

    /**
     * @hide
     */
    public VirtualSensor(int handle, int type, String name, IVirtualDevice virtualDevice,
            IBinder token) {
        mHandle = handle;
        mType = type;
        mName = name;
        mVirtualDevice = virtualDevice;
        mToken = token;
    }

    private VirtualSensor(Parcel parcel) {
        mHandle = parcel.readInt();
        mType = parcel.readInt();
        mName = parcel.readString8();
        mVirtualDevice = IVirtualDevice.Stub.asInterface(parcel.readStrongBinder());
        mToken = parcel.readStrongBinder();
    }

    /**
     * Returns the unique handle of the sensor.
     *
     * @hide
     */
    public int getHandle() {
        return mHandle;
    }

    /**
     * Returns the type of the sensor.
     *
     * @see Sensor#getType()
     * @see <a href="https://source.android.com/devices/sensors/sensor-types">Sensor types</a>
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the name of the sensor.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the identifier of the
     * {@link android.companion.virtual.VirtualDeviceManager.VirtualDevice} this sensor belongs to.
     */
    public int getDeviceId() {
        try {
            return mVirtualDevice.getDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mHandle);
        parcel.writeInt(mType);
        parcel.writeString8(mName);
        parcel.writeStrongBinder(mVirtualDevice.asBinder());
        parcel.writeStrongBinder(mToken);
    }

    @Override
    public String toString() {
        return "VirtualSensor{ mType=" + mType + ", mName='" + mName + "' }";
    }

    /**
     * Send a sensor event to the system.
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendEvent(@NonNull VirtualSensorEvent event) {
        try {
            mVirtualDevice.sendSensorEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualSensor> CREATOR =
            new Parcelable.Creator<VirtualSensor>() {
                public VirtualSensor createFromParcel(Parcel in) {
                    return new VirtualSensor(in);
                }

                public VirtualSensor[] newArray(int size) {
                    return new VirtualSensor[size];
                }
            };
}
