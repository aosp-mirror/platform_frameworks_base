/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * A class representing a UWB address
 *
 * @hide
 */
public final class UwbAddress implements Parcelable {
    public static final int SHORT_ADDRESS_BYTE_LENGTH = 2;
    public static final int EXTENDED_ADDRESS_BYTE_LENGTH = 8;

    private final byte[] mAddressBytes;

    private UwbAddress(byte[] address) {
        mAddressBytes = address;
    }

    /**
     * Create a {@link UwbAddress} from a byte array.
     *
     * <p>If the provided array is {@link #SHORT_ADDRESS_BYTE_LENGTH} bytes, a short address is
     * created. If the provided array is {@link #EXTENDED_ADDRESS_BYTE_LENGTH} bytes, then an
     * extended address is created.
     *
     * @param address a byte array to convert to a {@link UwbAddress}
     * @return a {@link UwbAddress} created from the input byte array
     * @throws IllegalArgumentException when the length is not one of
     *       {@link #SHORT_ADDRESS_BYTE_LENGTH} or {@link #EXTENDED_ADDRESS_BYTE_LENGTH} bytes
     */
    @NonNull
    public static UwbAddress fromBytes(@NonNull byte[] address) {
        if (address.length != SHORT_ADDRESS_BYTE_LENGTH
                && address.length != EXTENDED_ADDRESS_BYTE_LENGTH) {
            throw new IllegalArgumentException("Invalid UwbAddress length " + address.length);
        }
        return new UwbAddress(address);
    }

    /**
     * Get the address as a byte array
     *
     * @return the byte representation of this {@link UwbAddress}
     */
    @NonNull
    public byte[] toBytes() {
        return mAddressBytes;
    }

    /**
     * The length of the address in bytes
     * <p>Possible values are {@link #SHORT_ADDRESS_BYTE_LENGTH} and
     * {@link #EXTENDED_ADDRESS_BYTE_LENGTH}.
     */
    public int size() {
        return mAddressBytes.length;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("0x");
        for (byte addressByte : mAddressBytes) {
            builder.append(String.format("%02X", addressByte));
        }
        return builder.toString();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UwbAddress) {
            return Arrays.equals(mAddressBytes, ((UwbAddress) obj).toBytes());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mAddressBytes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAddressBytes.length);
        dest.writeByteArray(mAddressBytes);
    }

    public static final @android.annotation.NonNull Creator<UwbAddress> CREATOR =
            new Creator<UwbAddress>() {
                @Override
                public UwbAddress createFromParcel(Parcel in) {
                    byte[] address = new byte[in.readInt()];
                    in.readByteArray(address);
                    return UwbAddress.fromBytes(address);
                }

                @Override
                public UwbAddress[] newArray(int size) {
                    return new UwbAddress[size];
                }
    };
}
