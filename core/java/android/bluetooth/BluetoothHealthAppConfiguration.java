/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.os.Parcelable;

/**
 * The Bluetooth Health Application Configuration that is used in conjunction with
 * the {@link BluetoothHealth} class. This class represents an application configuration
 * that the Bluetooth Health third party application will register to communicate with the
 * remote Bluetooth health device.
 *
 * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
 * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
 * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
 * {@link BluetoothDevice#createL2capChannel(int)}
 */
@Deprecated
public final class BluetoothHealthAppConfiguration implements Parcelable {
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Return the data type associated with this application configuration.
     *
     * @return dataType
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public int getDataType() {
        return 0;
    }

    /**
     * Return the name of the application configuration.
     *
     * @return String name
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public String getName() {
        return null;
    }

    /**
     * Return the role associated with this application configuration.
     *
     * @return One of {@link BluetoothHealth#SOURCE_ROLE} or {@link BluetoothHealth#SINK_ROLE}
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public int getRole() {
        return 0;
    }

    /**
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()(int)}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothHealthAppConfiguration> CREATOR =
            new Parcelable.Creator<BluetoothHealthAppConfiguration>() {
                @Override
                public BluetoothHealthAppConfiguration createFromParcel(Parcel in) {
                    return new BluetoothHealthAppConfiguration();
                }

                @Override
                public BluetoothHealthAppConfiguration[] newArray(int size) {
                    return new BluetoothHealthAppConfiguration[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {}
}
