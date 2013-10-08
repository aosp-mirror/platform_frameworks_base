/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.bluetooth;

import java.util.Arrays;
import java.util.UUID;
import android.os.Parcel;
import android.os.Parcelable;

/**
* Bluetooth Low Energy services UUID.
*/
/** @hide */
public final class BluetoothLEServiceUuid implements Parcelable{
    /** @hide */
    public final byte type;
    /** @hide */
    public final UUID id;

    /* Bluetooth Low Energy Service UUID type */
    /** @hide */
    public static final byte BLE_SERVICE_UUID_NONE_TYPE = 0x00;
    /** @hide */
    public static final byte BLE_SERVICE_16Bit_UUID_TYPE = 0x03;
    /** @hide */
    public static final byte BLE_SERVICE_32Bit_UUID_TYPE = 0x05;
    /** @hide */
    public static final byte BLE_SERVICE_128Bit_UUID_TYPE = 0x07;
    /** @hide */
    public static final byte BLE_SERVICE_16Bit_SLC_UUID_TYPE = 0x14; /* 16 bit solicitation service uuid */
    /** @hide */
    public static final byte BLE_SERVICE_128Bit_SLC_UUID_TYPE = 0x15; /* 128 bit solicitation service uuid */

    /* Bluetoth Low Energy Service UUID value */
    /** @hide */
    public static final UUID BLE_SERVICE_EXAMPLE_UUID_VALUE = new UUID(0x0000110F00001000L, 0x800000805F9B34FBL); /* type: 0x15 */

    /** @hide */
    public BluetoothLEServiceUuid (UUID id)
    {
        this.type = BLE_SERVICE_UUID_NONE_TYPE;
        this.id = id;
    }
    /** @hide */
    public BluetoothLEServiceUuid (byte type, UUID id)
    {
        this.type = type;
        this.id = id;
    }
    /** @hide */
    public byte getType()
    {
        return this.type;
    }
    /** @hide */
    public long getLeastSignificantBits()
    {
        return id.getLeastSignificantBits();
    }
    /** @hide */
    public long getMostSignificantBits()
    {
        return id.getMostSignificantBits();
    }
    /** @hide */
    public int describeContents()
    {
        return 0;
    }
    /** @hide */
    public void writeToParcel(Parcel out, int flags)
    {
        out.writeByte(this.type);
        out.writeLong(getLeastSignificantBits());
        out.writeLong(getMostSignificantBits());
    }
    /** @hide */
    public static final Parcelable.Creator<BluetoothLEServiceUuid> CREATOR =
               new Parcelable.Creator<BluetoothLEServiceUuid>() {
        public BluetoothLEServiceUuid createFromParcel(Parcel source) {
        byte type = source.readByte();
        long lsb = source.readLong();
        long msb = source.readLong();
        return new BluetoothLEServiceUuid(type, new UUID(msb, lsb));
    }
    /** @hide */
    public BluetoothLEServiceUuid[] newArray(int size) {
        return new BluetoothLEServiceUuid[size];
    }
    };
}
