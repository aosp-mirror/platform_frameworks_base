/*
 * Copyright 2017 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * @hide
 */
@SystemApi
public final class NanoAppBinary implements Parcelable {
    private static final String TAG = "NanoAppBinary";

    /*
     * The contents of the app binary.
     */
    private byte[] mNanoAppBinary;

    /*
     * Contents of the nanoapp binary header.
     *
     * Only valid if mHasValidHeader is true.
     * See nano_app_binary_t in context_hub.h for details.
     */
    private int mHeaderVersion;
    private int mMagic;
    private long mNanoAppId;
    private int mNanoAppVersion;
    private int mFlags;
    private long mHwHubType;
    private byte mTargetChreApiMajorVersion;
    private byte mTargetChreApiMinorVersion;

    private boolean mHasValidHeader = false;

    /*
     * The header version used to parse the binary in parseBinaryHeader().
     */
    private static final int EXPECTED_HEADER_VERSION = 1;

    /*
     * The magic value expected in the header as defined in context_hub.h.
     */
    private static final int EXPECTED_MAGIC_VALUE =
            (((int) 'N' <<  0) | ((int) 'A' <<  8) | ((int) 'N' << 16) | ((int) 'O' << 24));

    /*
     * Byte order established in context_hub.h
     */
    private static final ByteOrder HEADER_ORDER = ByteOrder.LITTLE_ENDIAN;

    /*
     * The size of the header in bytes as defined in context_hub.h.
     */
    private static final int HEADER_SIZE_BYTES = 40;

    /*
     * The bit fields for mFlags as defined in context_hub.h.
     */
    private static final int NANOAPP_SIGNED_FLAG_BIT = 0x1;
    private static final int NANOAPP_ENCRYPTED_FLAG_BIT = 0x2;

    public NanoAppBinary(byte[] appBinary) {
        mNanoAppBinary = appBinary;
        parseBinaryHeader();
    }

    /*
     * Parses the binary header and populates its field using mNanoAppBinary.
     */
    private void parseBinaryHeader() {
        ByteBuffer buf = ByteBuffer.wrap(mNanoAppBinary).order(HEADER_ORDER);

        mHasValidHeader = false;
        try {
            mHeaderVersion = buf.getInt();
            if (mHeaderVersion != EXPECTED_HEADER_VERSION) {
                Log.e(TAG, "Unexpected header version " + mHeaderVersion + " while parsing header"
                        + " (expected " + EXPECTED_HEADER_VERSION + ")");
                return;
            }

            mMagic = buf.getInt();
            mNanoAppId = buf.getLong();
            mNanoAppVersion = buf.getInt();
            mFlags = buf.getInt();
            mHwHubType = buf.getLong();
            mTargetChreApiMajorVersion = buf.get();
            mTargetChreApiMinorVersion = buf.get();
        } catch (BufferUnderflowException e) {
            Log.e(TAG, "Not enough contents in nanoapp header");
            return;
        }

        if (mMagic != EXPECTED_MAGIC_VALUE) {
            Log.e(TAG, "Unexpected magic value " + String.format("0x%08X", mMagic)
                    + "while parsing header (expected "
                    + String.format("0x%08X", EXPECTED_MAGIC_VALUE) + ")");
        } else {
            mHasValidHeader = true;
        }
    }

    /**
     * @return the app binary byte array
     */
    public byte[] getBinary() {
        return mNanoAppBinary;
    }

    /**
     * @return the app binary byte array without the leading header
     *
     * @throws IndexOutOfBoundsException if the nanoapp binary size is smaller than the header size
     * @throws NullPointerException if the nanoapp binary is null
     */
    public byte[] getBinaryNoHeader() {
        if (mNanoAppBinary.length < HEADER_SIZE_BYTES) {
            throw new IndexOutOfBoundsException("NanoAppBinary binary byte size ("
                + mNanoAppBinary.length + ") is less than header size (" + HEADER_SIZE_BYTES + ")");
        }

        return Arrays.copyOfRange(mNanoAppBinary, HEADER_SIZE_BYTES, mNanoAppBinary.length);
    }

    /**
     * @return {@code true} if the header is valid, {@code false} otherwise
     */
    public boolean hasValidHeader() {
        return mHasValidHeader;
    }

    /**
     * @return the header version
     */
    public int getHeaderVersion() {
        return mHeaderVersion;
    }

    /**
     * @return the app ID parsed from the nanoapp header
     */
    public long getNanoAppId() {
        return mNanoAppId;
    }

    /**
     * @return the app version parsed from the nanoapp header
     */
    public int getNanoAppVersion() {
        return mNanoAppVersion;
    }

    /**
     * @return the compile target hub type parsed from the nanoapp header
     */
    public long getHwHubType() {
        return mHwHubType;
    }

    /**
     * @return the target CHRE API major version parsed from the nanoapp header
     */
    public byte getTargetChreApiMajorVersion() {
        return mTargetChreApiMajorVersion;
    }

    /**
     * @return the target CHRE API minor version parsed from the nanoapp header
     */
    public byte getTargetChreApiMinorVersion() {
        return mTargetChreApiMinorVersion;
    }

    /**
     * Returns the flags for the nanoapp as defined in context_hub.h.
     *
     * This method is meant to be used by the Context Hub Service.
     *
     * @return the flags for the nanoapp
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * @return {@code true} if the nanoapp binary is signed, {@code false} otherwise
     */
    public boolean isSigned() {
        return (mFlags & NANOAPP_SIGNED_FLAG_BIT) != 0;
    }

    /**
     * @return {@code true} if the nanoapp binary is encrypted, {@code false} otherwise
     */
    public boolean isEncrypted() {
        return (mFlags & NANOAPP_ENCRYPTED_FLAG_BIT) != 0;
    }

    private NanoAppBinary(Parcel in) {
        int binaryLength = in.readInt();
        mNanoAppBinary = new byte[binaryLength];
        in.readByteArray(mNanoAppBinary);

        parseBinaryHeader();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mNanoAppBinary.length);
        out.writeByteArray(mNanoAppBinary);
    }

    public static final Creator<NanoAppBinary> CREATOR =
            new Creator<NanoAppBinary>() {
                @Override
                public NanoAppBinary createFromParcel(Parcel in) {
                    return new NanoAppBinary(in);
                }

                @Override
                public NanoAppBinary[] newArray(int size) {
                    return new NanoAppBinary[size];
                }
            };
}
