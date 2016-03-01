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
import android.os.Parcelable;

import android.util.Log;

/**
 * Out Of Band Data for Bluetooth device.
 */
public final class OobData implements Parcelable {
    private byte[] securityManagerTk;

    public byte[] getSecurityManagerTk() {
        return securityManagerTk;
    }

    public void setSecurityManagerTk(byte[] securityManagerTk) {
        this.securityManagerTk = securityManagerTk;
    }

    public OobData() { }

    private OobData(Parcel in) {
        securityManagerTk = in.createByteArray();
    }

    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(securityManagerTk);
    }

    public static final Parcelable.Creator<OobData> CREATOR
            = new Parcelable.Creator<OobData>() {
        public OobData createFromParcel(Parcel in) {
            return new OobData(in);
        }

        public OobData[] newArray(int size) {
            return new OobData[size];
        }
    };
}