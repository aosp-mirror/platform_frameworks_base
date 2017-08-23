/*
 * Copyright (C) 2014 The Android Open Source Project
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
            return mId == ((BluetoothMasInstance) o).mId;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mId + (mChannel << 8) + (mMsgTypes << 16);
    }

    @Override
    public String toString() {
        return Integer.toString(mId) + ":" + mName + ":" + mChannel + ":"
                + Integer.toHexString(mMsgTypes);
    }

    @Override
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

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mName);
        out.writeInt(mChannel);
        out.writeInt(mMsgTypes);
    }

    public static final class MessageType {
        public static final int EMAIL = 0x01;
        public static final int SMS_GSM = 0x02;
        public static final int SMS_CDMA = 0x04;
        public static final int MMS = 0x08;
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
