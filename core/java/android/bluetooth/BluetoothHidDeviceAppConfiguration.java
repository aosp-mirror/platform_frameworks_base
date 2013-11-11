/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
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

import java.util.Random;

/** @hide */
public final class BluetoothHidDeviceAppConfiguration implements Parcelable {
    private final long mHash;

    BluetoothHidDeviceAppConfiguration() {
        Random rnd = new Random();
        mHash = rnd.nextLong();
    }

    BluetoothHidDeviceAppConfiguration(long hash) {
        mHash = hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothHidDeviceAppConfiguration) {
            BluetoothHidDeviceAppConfiguration config = (BluetoothHidDeviceAppConfiguration) o;
            return mHash == config.mHash;
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothHidDeviceAppConfiguration> CREATOR =
        new Parcelable.Creator<BluetoothHidDeviceAppConfiguration>() {

        @Override
        public BluetoothHidDeviceAppConfiguration createFromParcel(Parcel in) {
            long hash = in.readLong();
            return new BluetoothHidDeviceAppConfiguration(hash);
        }

        @Override
        public BluetoothHidDeviceAppConfiguration[] newArray(int size) {
            return new BluetoothHidDeviceAppConfiguration[size];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mHash);
    }
}
