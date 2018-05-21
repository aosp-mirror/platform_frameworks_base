/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.net;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * This class represents a single algorithm that can be used by an {@link IpSecTransform}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4301">RFC 4301, Security Architecture for the
 * Internet Protocol</a>
 */
public final class IpSecAlgorithm implements Parcelable {
    private static final String TAG = "IpSecAlgorithm";

    /**
     * Null cipher.
     *
     * @hide
     */
    public static final String CRYPT_NULL = "ecb(cipher_null)";

    /**
     * AES-CBC Encryption/Ciphering Algorithm.
     *
     * <p>Valid lengths for this key are {128, 192, 256}.
     */
    public static final String CRYPT_AES_CBC = "cbc(aes)";

    /**
     * MD5 HMAC Authentication/Integrity Algorithm. <b>This algorithm is not recommended for use in
     * new applications and is provided for legacy compatibility with 3gpp infrastructure.</b>
     *
     * <p>Keys for this algorithm must be 128 bits in length.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to 128.
     */
    public static final String AUTH_HMAC_MD5 = "hmac(md5)";

    /**
     * SHA1 HMAC Authentication/Integrity Algorithm. <b>This algorithm is not recommended for use in
     * new applications and is provided for legacy compatibility with 3gpp infrastructure.</b>
     *
     * <p>Keys for this algorithm must be 160 bits in length.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to 160.
     */
    public static final String AUTH_HMAC_SHA1 = "hmac(sha1)";

    /**
     * SHA256 HMAC Authentication/Integrity Algorithm.
     *
     * <p>Keys for this algorithm must be 256 bits in length.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to 256.
     */
    public static final String AUTH_HMAC_SHA256 = "hmac(sha256)";

    /**
     * SHA384 HMAC Authentication/Integrity Algorithm.
     *
     * <p>Keys for this algorithm must be 384 bits in length.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 192 to 384.
     */
    public static final String AUTH_HMAC_SHA384 = "hmac(sha384)";

    /**
     * SHA512 HMAC Authentication/Integrity Algorithm.
     *
     * <p>Keys for this algorithm must be 512 bits in length.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 256 to 512.
     */
    public static final String AUTH_HMAC_SHA512 = "hmac(sha512)";

    /**
     * AES-GCM Authentication/Integrity + Encryption/Ciphering Algorithm.
     *
     * <p>Valid lengths for keying material are {160, 224, 288}.
     *
     * <p>As per <a href="https://tools.ietf.org/html/rfc4106#section-8.1">RFC4106 (Section
     * 8.1)</a>, keying material consists of a 128, 192, or 256 bit AES key followed by a 32-bit
     * salt. RFC compliance requires that the salt must be unique per invocation with the same key.
     *
     * <p>Valid ICV (truncation) lengths are {64, 96, 128}.
     */
    public static final String AUTH_CRYPT_AES_GCM = "rfc4106(gcm(aes))";

    /** @hide */
    @StringDef({
        CRYPT_AES_CBC,
        AUTH_HMAC_MD5,
        AUTH_HMAC_SHA1,
        AUTH_HMAC_SHA256,
        AUTH_HMAC_SHA384,
        AUTH_HMAC_SHA512,
        AUTH_CRYPT_AES_GCM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlgorithmName {}

    private final String mName;
    private final byte[] mKey;
    private final int mTruncLenBits;

    /**
     * Creates an IpSecAlgorithm of one of the supported types. Supported algorithm names are
     * defined as constants in this class.
     *
     * <p>For algorithms that produce an integrity check value, the truncation length is a required
     * parameter. See {@link #IpSecAlgorithm(String algorithm, byte[] key, int truncLenBits)}
     *
     * @param algorithm name of the algorithm.
     * @param key key padded to a multiple of 8 bits.
     */
    public IpSecAlgorithm(@NonNull @AlgorithmName String algorithm, @NonNull byte[] key) {
        this(algorithm, key, 0);
    }

    /**
     * Creates an IpSecAlgorithm of one of the supported types. Supported algorithm names are
     * defined as constants in this class.
     *
     * <p>This constructor only supports algorithms that use a truncation length. i.e.
     * Authentication and Authenticated Encryption algorithms.
     *
     * @param algorithm name of the algorithm.
     * @param key key padded to a multiple of 8 bits.
     * @param truncLenBits number of bits of output hash to use.
     */
    public IpSecAlgorithm(
            @NonNull @AlgorithmName String algorithm, @NonNull byte[] key, int truncLenBits) {
        mName = algorithm;
        mKey = key.clone();
        mTruncLenBits = truncLenBits;
        checkValidOrThrow(mName, mKey.length * 8, mTruncLenBits);
    }

    /** Get the algorithm name */
    @NonNull
    public String getName() {
        return mName;
    }

    /** Get the key for this algorithm */
    @NonNull
    public byte[] getKey() {
        return mKey.clone();
    }

    /** Get the truncation length of this algorithm, in bits */
    public int getTruncationLengthBits() {
        return mTruncLenBits;
    }

    /* Parcelable Implementation */
    public int describeContents() {
        return 0;
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mName);
        out.writeByteArray(mKey);
        out.writeInt(mTruncLenBits);
    }

    /** Parcelable Creator */
    public static final Parcelable.Creator<IpSecAlgorithm> CREATOR =
            new Parcelable.Creator<IpSecAlgorithm>() {
                public IpSecAlgorithm createFromParcel(Parcel in) {
                    final String name = in.readString();
                    final byte[] key = in.createByteArray();
                    final int truncLenBits = in.readInt();

                    return new IpSecAlgorithm(name, key, truncLenBits);
                }

                public IpSecAlgorithm[] newArray(int size) {
                    return new IpSecAlgorithm[size];
                }
            };

    private static void checkValidOrThrow(String name, int keyLen, int truncLen) {
        boolean isValidLen = true;
        boolean isValidTruncLen = true;

        switch(name) {
            case CRYPT_AES_CBC:
                isValidLen = keyLen == 128 || keyLen == 192 || keyLen == 256;
                break;
            case AUTH_HMAC_MD5:
                isValidLen = keyLen == 128;
                isValidTruncLen = truncLen >= 96 && truncLen <= 128;
                break;
            case AUTH_HMAC_SHA1:
                isValidLen = keyLen == 160;
                isValidTruncLen = truncLen >= 96 && truncLen <= 160;
                break;
            case AUTH_HMAC_SHA256:
                isValidLen = keyLen == 256;
                isValidTruncLen = truncLen >= 96 && truncLen <= 256;
                break;
            case AUTH_HMAC_SHA384:
                isValidLen = keyLen == 384;
                isValidTruncLen = truncLen >= 192 && truncLen <= 384;
                break;
            case AUTH_HMAC_SHA512:
                isValidLen = keyLen == 512;
                isValidTruncLen = truncLen >= 256 && truncLen <= 512;
                break;
            case AUTH_CRYPT_AES_GCM:
                // The keying material for GCM is a key plus a 32-bit salt
                isValidLen = keyLen == 128 + 32 || keyLen == 192 + 32 || keyLen == 256 + 32;
                isValidTruncLen = truncLen == 64 || truncLen == 96 || truncLen == 128;
                break;
            default:
                throw new IllegalArgumentException("Couldn't find an algorithm: " + name);
        }

        if (!isValidLen) {
            throw new IllegalArgumentException("Invalid key material keyLength: " + keyLen);
        }
        if (!isValidTruncLen) {
            throw new IllegalArgumentException("Invalid truncation keyLength: " + truncLen);
        }
    }

    /** @hide */
    public boolean isAuthentication() {
        switch (getName()) {
            // Fallthrough
            case AUTH_HMAC_MD5:
            case AUTH_HMAC_SHA1:
            case AUTH_HMAC_SHA256:
            case AUTH_HMAC_SHA384:
            case AUTH_HMAC_SHA512:
                return true;
            default:
                return false;
        }
    }

    /** @hide */
    public boolean isEncryption() {
        return getName().equals(CRYPT_AES_CBC);
    }

    /** @hide */
    public boolean isAead() {
        return getName().equals(AUTH_CRYPT_AES_GCM);
    }

    // Because encryption keys are sensitive and userdebug builds are used by large user pools
    // such as beta testers, we only allow sensitive info such as keys on eng builds.
    private static boolean isUnsafeBuild() {
        return Build.IS_DEBUGGABLE && Build.IS_ENG;
    }

    @Override
    @NonNull
    public String toString() {
        return new StringBuilder()
                .append("{mName=")
                .append(mName)
                .append(", mKey=")
                .append(isUnsafeBuild() ? HexDump.toHexString(mKey) : "<hidden>")
                .append(", mTruncLenBits=")
                .append(mTruncLenBits)
                .append("}")
                .toString();
    }

    /** @hide */
    @VisibleForTesting
    public static boolean equals(IpSecAlgorithm lhs, IpSecAlgorithm rhs) {
        if (lhs == null || rhs == null) return (lhs == rhs);
        return (lhs.mName.equals(rhs.mName)
                && Arrays.equals(lhs.mKey, rhs.mKey)
                && lhs.mTruncLenBits == rhs.mTruncLenBits);
    }
};
