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
 * Out Of Band Data for Bluetooth device pairing.
 *
 * <p>This object represents optional data obtained from a remote device through
 * an out-of-band channel (eg. NFC).
 *
 * @hide
 */
public class OobData implements Parcelable {
    private byte[] leBluetoothDeviceAddress;
    private byte[] securityManagerTk;
    private byte[] leSecureConnectionsConfirmation;
    private byte[] leSecureConnectionsRandom;

    public byte[] getLeBluetoothDeviceAddress() {
        return leBluetoothDeviceAddress;
    }

    /**
     * Sets the LE Bluetooth Device Address value to be used during LE pairing.
     * The value shall be 7 bytes. Please see Bluetooth CSSv6, Part A 1.16 for
     * a detailed description.
     */
    public void setLeBluetoothDeviceAddress(byte[] leBluetoothDeviceAddress) {
        this.leBluetoothDeviceAddress = leBluetoothDeviceAddress;
    }

    public byte[] getSecurityManagerTk() {
        return securityManagerTk;
    }

    /**
     * Sets the Temporary Key value to be used by the LE Security Manager during
     * LE pairing. The value shall be 16 bytes. Please see Bluetooth CSSv6,
     * Part A 1.8 for a detailed description.
     */
    public void setSecurityManagerTk(byte[] securityManagerTk) {
        this.securityManagerTk = securityManagerTk;
    }

    public byte[] getLeSecureConnectionsConfirmation() {
        return leSecureConnectionsConfirmation;
    }

    public void setLeSecureConnectionsConfirmation(byte[] leSecureConnectionsConfirmation) {
        this.leSecureConnectionsConfirmation = leSecureConnectionsConfirmation;
    }

    public byte[] getLeSecureConnectionsRandom() {
        return leSecureConnectionsRandom;
    }

    public void setLeSecureConnectionsRandom(byte[] leSecureConnectionsRandom) {
        this.leSecureConnectionsRandom = leSecureConnectionsRandom;
    }

    public OobData() { }

    private OobData(Parcel in) {
        leBluetoothDeviceAddress = in.createByteArray();
        securityManagerTk = in.createByteArray();
        leSecureConnectionsConfirmation = in.createByteArray();
        leSecureConnectionsRandom = in.createByteArray();
    }

    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(leBluetoothDeviceAddress);
        out.writeByteArray(securityManagerTk);
        out.writeByteArray(leSecureConnectionsConfirmation);
        out.writeByteArray(leSecureConnectionsRandom);
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