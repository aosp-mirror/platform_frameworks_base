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

/**
 * File            : LLCPPacket.java
 * Original-Author : Trusted Logic S.A. (Daniel Tomas)
 * Created         : 25-02-2010
 */

package com.trustedlogic.trustednfc.android;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a LLCP packet received in a LLCP Connectionless communication;
 * 
 * @since AA02.01
 * @hide
 */
public class LlcpPacket implements Parcelable {

    private int mRemoteSap;

    private byte[] mDataBuffer;

    /**
     * Creator class, needed when implementing from Parcelable
     * {@hide}
     */
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
    

    /**
     * Creates a LlcpPacket to be sent to a remote Service Access Point number
     * (SAP)
     * 
     * @param sap Remote Service Access Point number
     * @param data Data buffer
     * @since AA02.01
     */
    public LlcpPacket(int sap, byte[] data) {
    	mRemoteSap = sap;
        mDataBuffer = data;
    }
    
    /**
     * @hide
     */
    public LlcpPacket() {
    }

    /**
     * Returns the remote Service Access Point number
     * 
     * @return remoteSap
     * @since AA02.01
     */
    public int getRemoteSap() {
        return mRemoteSap;
    }

    /**
     * Returns the data buffer
     * 
     * @return data
     * @since AA02.01
     */
    public byte[] getDataBuffer() {
        return mDataBuffer;
    }

    /**
     * (Parcelable) Describe the parcel
     * {@hide}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * (Parcelable) Convert current object to a Parcel
     * {@hide}
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRemoteSap);
        dest.writeInt(mDataBuffer.length);
        dest.writeByteArray(mDataBuffer);      
    }
}
