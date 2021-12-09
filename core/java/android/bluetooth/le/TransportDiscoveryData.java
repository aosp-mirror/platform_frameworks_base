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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wrapper for Transport Discovery Data AD Type.
 * This class contains the Transport Discovery Data AD Type Code as well as
 * a list of potential Transport Blocks.
 *
 * @see AdvertiseData
 */
public final class TransportDiscoveryData implements Parcelable {
    private static final String TAG = "TransportDiscoveryData";
    private final int mTransportDataType;
    private final List<TransportBlock> mTransportBlocks;

    /**
     * Creates a TransportDiscoveryData instance.
     *
     * @param transportDataType the Transport Discovery Data AD Type
     * @param transportBlocks the list of Transport Blocks
     */
    public TransportDiscoveryData(int transportDataType,
            @NonNull List<TransportBlock> transportBlocks) {
        mTransportDataType = transportDataType;
        mTransportBlocks = transportBlocks;
    }

    /**
     * Creates a TransportDiscoveryData instance from byte arrays.
     *
     * Uses the transport discovery data bytes and parses them into an usable class.
     *
     * @param transportDiscoveryData the raw discovery data
     */
    public TransportDiscoveryData(@NonNull byte[] transportDiscoveryData) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(transportDiscoveryData);
        mTransportBlocks = new ArrayList();
        if (byteBuffer.remaining() > 0) {
            mTransportDataType = byteBuffer.get();
        } else {
            mTransportDataType = -1;
        }
        try {
            while (byteBuffer.remaining() > 0) {
                int orgId = byteBuffer.get();
                int tdsFlags = byteBuffer.get();
                int transportDataLength = byteBuffer.get();
                byte[] transportData = new byte[transportDataLength];
                byteBuffer.get(transportData, 0, transportDataLength);
                mTransportBlocks.add(new TransportBlock(orgId, tdsFlags,
                        transportDataLength, transportData));
            }
        } catch (BufferUnderflowException e) {
            Log.e(TAG, "Error while parsing data: " + e.toString());
        }
    }

    private TransportDiscoveryData(Parcel in) {
        mTransportDataType = in.readInt();
        mTransportBlocks = in.createTypedArrayList(TransportBlock.CREATOR);
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
        TransportDiscoveryData other = (TransportDiscoveryData) obj;
        return Arrays.equals(toByteArray(), other.toByteArray());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTransportDataType);
        dest.writeTypedList(mTransportBlocks);
    }

    public static final @NonNull Creator<TransportDiscoveryData> CREATOR =
            new Creator<TransportDiscoveryData>() {
                @Override
                public TransportDiscoveryData createFromParcel(Parcel in) {
                    return new TransportDiscoveryData(in);
                }

                @Override
                public TransportDiscoveryData[] newArray(int size) {
                    return new TransportDiscoveryData[size];
                }
    };

    /**
     * Gets the transport data type.
     */
    public int getTransportDataType() {
        return mTransportDataType;
    }

    /**
     * @return the list of {@link TransportBlock} in this TransportDiscoveryData
     *         or an empty list if there are no Transport Blocks
     */
    @NonNull
    public List<TransportBlock> getTransportBlocks() {
        if (mTransportBlocks == null) {
            return Collections.emptyList();
        }
        return mTransportBlocks;
    }

    /**
     * Converts this TransportDiscoveryData to byte array
     *
     * @return byte array representation of this Transport Discovery Data or null if the
     *         conversion failed
     */
    @Nullable
    public byte[] toByteArray() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(totalBytes());
            buffer.put((byte) mTransportDataType);
            for (TransportBlock transportBlock : getTransportBlocks()) {
                buffer.put(transportBlock.toByteArray());
            }
            return buffer.array();
        } catch (BufferOverflowException e) {
            Log.e(TAG, "Error converting to byte array: " + e.toString());
            return null;
        }
    }

    /**
     * @return total byte count of this TransportDataDiscovery
     */
    public int totalBytes() {
        int size = 1; // Counting Transport Data Type here.
        for (TransportBlock transportBlock : getTransportBlocks()) {
            size += transportBlock.totalBytes();
        }
        return size;
    }
}
