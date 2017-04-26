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

import android.annotation.StringDef;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * IpSecAlgorithm specifies a single algorithm that can be applied to an IpSec Transform. Refer to
 * RFC 4301.
 *
 * @hide
 */
public final class IpSecAlgorithm implements Parcelable {

    /**
     * AES-CBC Encryption/Ciphering Algorithm.
     *
     * <p>Valid lengths for this key are {128, 192, 256}.
     */
    public static final String CRYPT_AES_CBC = "cbc(aes)";

    /**
     * MD5 HMAC Authentication/Integrity Algorithm. This algorithm is not recommended for use in new
     * applications and is provided for legacy compatibility with 3gpp infrastructure.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to (default) 128.
     */
    public static final String AUTH_HMAC_MD5 = "hmac(md5)";

    /**
     * SHA1 HMAC Authentication/Integrity Algorithm. This algorithm is not recommended for use in
     * new applications and is provided for legacy compatibility with 3gpp infrastructure.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to (default) 160.
     */
    public static final String AUTH_HMAC_SHA1 = "hmac(sha1)";

    /**
     * SHA256 HMAC Authentication/Integrity Algorithm.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 96 to (default) 256.
     */
    public static final String AUTH_HMAC_SHA256 = "hmac(sha256)";

    /**
     * SHA384 HMAC Authentication/Integrity Algorithm.
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 192 to (default) 384.
     */
    public static final String AUTH_HMAC_SHA384 = "hmac(sha384)";
    /**
     * SHA512 HMAC Authentication/Integrity Algorithm
     *
     * <p>Valid truncation lengths are multiples of 8 bits from 256 to (default) 512.
     */
    public static final String AUTH_HMAC_SHA512 = "hmac(sha512)";

    /** @hide */
    @StringDef({
        CRYPT_AES_CBC,
        AUTH_HMAC_MD5,
        AUTH_HMAC_SHA1,
        AUTH_HMAC_SHA256,
        AUTH_HMAC_SHA512
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlgorithmName {}

    private final String mName;
    private final byte[] mKey;
    private final int mTruncLenBits;

    /**
     * Specify a IpSecAlgorithm of one of the supported types including the truncation length of the
     * algorithm
     *
     * @param algorithm type for IpSec.
     * @param key non-null Key padded to a multiple of 8 bits.
     */
    public IpSecAlgorithm(String algorithm, byte[] key) {
        this(algorithm, key, key.length * 8);
    }

    /**
     * Specify a IpSecAlgorithm of one of the supported types including the truncation length of the
     * algorithm
     *
     * @param algoName precise name of the algorithm to be used.
     * @param key non-null Key padded to a multiple of 8 bits.
     * @param truncLenBits the number of bits of output hash to use; only meaningful for
     *     Authentication.
     */
    public IpSecAlgorithm(@AlgorithmName String algoName, byte[] key, int truncLenBits) {
        if (!isTruncationLengthValid(algoName, truncLenBits)) {
            throw new IllegalArgumentException("Unknown algorithm or invalid length");
        }
        mName = algoName;
        mKey = key.clone();
        mTruncLenBits = Math.min(truncLenBits, key.length * 8);
    }

    /** Retrieve the algorithm name */
    public String getName() {
        return mName;
    }

    /** Retrieve the key for this algorithm */
    public byte[] getKey() {
        return mKey.clone();
    }

    /**
     * Retrieve the truncation length, in bits, for the key in this algo. By default this will be
     * the length in bits of the key.
     */
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
                    return new IpSecAlgorithm(in);
                }

                public IpSecAlgorithm[] newArray(int size) {
                    return new IpSecAlgorithm[size];
                }
            };

    private IpSecAlgorithm(Parcel in) {
        mName = in.readString();
        mKey = in.createByteArray();
        mTruncLenBits = in.readInt();
    }

    private static boolean isTruncationLengthValid(String algo, int truncLenBits) {
        switch (algo) {
            case CRYPT_AES_CBC:
                return (truncLenBits == 128 || truncLenBits == 192 || truncLenBits == 256);
            case AUTH_HMAC_MD5:
                return (truncLenBits >= 96 && truncLenBits <= 128);
            case AUTH_HMAC_SHA1:
                return (truncLenBits >= 96 && truncLenBits <= 160);
            case AUTH_HMAC_SHA256:
                return (truncLenBits >= 96 && truncLenBits <= 256);
            case AUTH_HMAC_SHA384:
                return (truncLenBits >= 192 && truncLenBits <= 384);
            case AUTH_HMAC_SHA512:
                return (truncLenBits >= 256 && truncLenBits <= 512);
            default:
                return false;
        }
    }
};
