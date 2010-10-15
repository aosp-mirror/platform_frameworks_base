/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a LLCP packet received in a LLCP Connectionless communication;
 * @hide
 */
public class LlcpPacket implements Parcelable {

    private final int mRemoteSap;

    private final byte[] mDataBuffer;

    /**
     * Creates a LlcpPacket to be sent to a remote Service Access Point number
     * (SAP)
     *
     * @param sap Remote Service Access Point number
     * @param data Data buffer
     */
    public LlcpPacket(int sap, byte[] data) {
        mRemoteSap = sap;
        mDataBuffer = data;
    }

    /**
     * Returns the remote Service Access Point number
     */
    public int getRemoteSap() {
        return mRemoteSap;
    }

    /**
     * Returns the data buffer
     */
    public byte[] getDataBuffer() {
        return mDataBuffer;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRemoteSap);
        dest.writeInt(mDataBuffer.length);
        dest.writeByteArray(mDataBuffer);
    }

    public static final Parcelable.Creator<LlcpPacket> CREATOR = new Parcelable.Creator<LlcpPacket>() {
        public LlcpPacket createFromParcel(Parcel in) {
            // Remote SAP
            short sap = (short)in.readInt();

            // Data Buffer
            int dataLength = in.readInt();
            byte[] data = new byte[dataLength];
            in.readByteArray(data);

            return new LlcpPacket(sap, data);
        }

        public LlcpPacket[] newArray(int size) {
            return new LlcpPacket[size];
        }
    };
}