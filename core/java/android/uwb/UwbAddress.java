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

/**
 * A class representing a UWB address
 *
 * @hide
 */
public final class UwbAddress {
    public static final int SHORT_ADDRESS_BYTE_LENGTH = 2;
    public static final int EXTENDED_ADDRESS_BYTE_LENGTH = 8;

    /**
     * Create a {@link UwbAddress} from a byte array.
     *
     * <p>If the provided array is {@link #SHORT_ADDRESS_BYTE_LENGTH} bytes, a short address is
     * created. If the provided array is {@link #EXTENDED_ADDRESS_BYTE_LENGTH} bytes, then an
     * extended address is created.
     *
     * @param address a byte array to convert to a {@link UwbAddress}
     * @return a {@link UwbAddress} created from the input byte array
     * @throw IllegableArumentException when the length is not one of
     *       {@link #SHORT_ADDRESS_BYTE_LENGTH} or {@link #EXTENDED_ADDRESS_BYTE_LENGTH} bytes
     */
    @NonNull
    public static UwbAddress fromBytes(byte[] address) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the address as a byte array
     *
     * @return the byte representation of this {@link UwbAddress}
     */
    @NonNull
    public byte[] toBytes() {
        throw new UnsupportedOperationException();
    }

    /**
     * The length of the address in bytes
     * <p>Possible values are {@link #SHORT_ADDRESS_BYTE_LENGTH} and
     * {@link #EXTENDED_ADDRESS_BYTE_LENGTH}.
     */
    public int size() {
        throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public String toString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException();
    }
}
