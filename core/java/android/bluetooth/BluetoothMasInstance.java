/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class BluetoothMasInstance implements Parcelable {
    private final int mId;
    private final String mName;
    private final int mChannel;
    private final int mMsgTypes;

    public BluetoothMasInstance(int id, String name, int channel, int msgTypes) {
        mId = id;
        mName = name;
        mChannel = channel;
        mMsgTypes = msgTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothMasInstance) {
            return mId == ((BluetoothMasInstance)o).mId;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mId + (mChannel << 8) + (mMsgTypes << 16);
    }

    @Override
    public String toString() {
        return Integer.toString(mId) + ":" + mName + ":" + mChannel + ":" +
                Integer.toHexString(mMsgTypes);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothMasInstance> CREATOR =
            new Parcelable.Creator<BluetoothMasInstance>() {
        public BluetoothMasInstance createFromParcel(Parcel in) {
            return new BluetoothMasInstance(in.readInt(), in.readString(),
                    in.readInt(), in.readInt());
        }
        public BluetoothMasInstance[] newArray(int size) {
            return new BluetoothMasInstance[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mName);
        out.writeInt(mChannel);
        out.writeInt(mMsgTypes);
    }

    public static final class MessageType {
        public static final int EMAIL    = 0x01;
        public static final int SMS_GSM  = 0x02;
        public static final int SMS_CDMA = 0x04;
        public static final int MMS      = 0x08;
    }

    public int getId() {
        return mId;
    }

    public String getName() {
        return mName;
    }

    public int getChannel() {
        return mChannel;
    }

    public int getMsgTypes() {
        return mMsgTypes;
    }

    public boolean msgSupported(int msg) {
        return (mMsgTypes & msg) != 0;
    }
}
