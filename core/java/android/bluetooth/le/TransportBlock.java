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

package android.bluetooth.le;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Wrapper for Transport Discovery Data Transport Blocks.
 * This class represents a Transport Block from a Transport Discovery Data.
 *
 * @see TransportDiscoveryData
 * @see AdvertiseData
 */
public final class TransportBlock implements Parcelable {
    private static final String TAG = "TransportBlock";
    private final int mOrgId;
    private final int mTdsFlags;
    private final int mTransportDataLength;
    private final byte[] mTransportData;

    /**
     * Creates an instance of TransportBlock from raw data.
     *
     * @param orgId the Organization ID
     * @param tdsFlags the TDS flags
     * @param transportDataLength the total length of the Transport Data
     * @param transportData the Transport Data
     */
    public TransportBlock(int orgId, int tdsFlags, int transportDataLength,
            @Nullable byte[] transportData) {
        mOrgId = orgId;
        mTdsFlags = tdsFlags;
        mTransportDataLength = transportDataLength;
        mTransportData = transportData;
    }

    private TransportBlock(Parcel in) {
        mOrgId = in.readInt();
        mTdsFlags = in.readInt();
        mTransportDataLength = in.readInt();
        if (mTransportDataLength > 0) {
            mTransportData = new byte[mTransportDataLength];
            in.readByteArray(mTransportData);
        } else {
            mTransportData = null;
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mOrgId);
        dest.writeInt(mTdsFlags);
        dest.writeInt(mTransportDataLength);
        if (mTransportData != null) {
            dest.writeByteArray(mTransportData);
        }
    }

    /**
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TransportBlock other = (TransportBlock) obj;
        return Arrays.equals(toByteArray(), other.toByteArray());
    }

    public static final @NonNull Creator<TransportBlock> CREATOR = new Creator<TransportBlock>() {
        @Override
        public TransportBlock createFromParcel(Parcel in) {
            return new TransportBlock(in);
        }

        @Override
        public TransportBlock[] newArray(int size) {
            return new TransportBlock[size];
        }
    };

    /**
     * Gets the Organization ID of the Transport Block which corresponds to one of the
     * the Bluetooth SIG Assigned Numbers.
     */
    public int getOrgId() {
        return mOrgId;
    }

    /**
     * Gets the TDS flags of the Transport Block which represents the role of the device and
     * information about its state and supported features.
     */
    public int getTdsFlags() {
        return mTdsFlags;
    }

    /**
     * Gets the total number of octets in the Transport Data field in this Transport Block.
     */
    public int getTransportDataLength() {
        return mTransportDataLength;
    }

    /**
     * Gets the Transport Data of the Transport Block which contains organization-specific data.
     */
    @Nullable
    public byte[] getTransportData() {
        return mTransportData;
    }

    /**
     * Converts this TransportBlock to byte array
     *
     * @return byte array representation of this Transport Block or null if the conversion failed
     */
    @Nullable
    public byte[] toByteArray() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(totalBytes());
            buffer.put((byte) mOrgId);
            buffer.put((byte) mTdsFlags);
            buffer.put((byte) mTransportDataLength);
            if (mTransportData != null) {
                buffer.put(mTransportData);
            }
            return buffer.array();
        } catch (BufferOverflowException e) {
            Log.e(TAG, "Error converting to byte array: " + e.toString());
            return null;
        }
    }

    /**
     * @return total byte count of this TransportBlock
     */
    public int totalBytes() {
        // 3 uint8 + byte[] length
        int size = 3 + mTransportDataLength;
        return size;
    }
}
