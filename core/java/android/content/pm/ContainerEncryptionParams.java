/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Slog;

import java.security.InvalidAlgorithmParameterException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Represents encryption parameters used to read a container.
 *
 * @deprecated encrypted containers are legacy.
 * @hide
 */
@SystemApi
@Deprecated
public class ContainerEncryptionParams implements Parcelable {
    protected static final String TAG = "ContainerEncryptionParams";

    /** What we print out first when toString() is called. */
    private static final String TO_STRING_PREFIX = "ContainerEncryptionParams{";

    /**
     * Parameter type for parceling that indicates the next parameters are
     * IvParameters.
     */
    private static final int ENC_PARAMS_IV_PARAMETERS = 1;

    /** Parameter type for paceling that indicates there are no MAC parameters. */
    private static final int MAC_PARAMS_NONE = 1;

    /** The encryption algorithm used. */
    private final String mEncryptionAlgorithm;

    /** The parameter spec to be used for encryption. */
    private final IvParameterSpec mEncryptionSpec;

    /** Secret key to be used for decryption. */
    private final SecretKey mEncryptionKey;

    /** Algorithm name for the MAC to be used. */
    private final String mMacAlgorithm;

    /** The parameter spec to be used for the MAC tag authentication. */
    private final AlgorithmParameterSpec mMacSpec;

    /** Secret key to be used for MAC tag authentication. */
    private final SecretKey mMacKey;

    /** MAC tag authenticating the data in the container. */
    private final byte[] mMacTag;

    /** Offset into file where authenticated (e.g., MAC protected) data begins. */
    private final long mAuthenticatedDataStart;

    /** Offset into file where encrypted data begins. */
    private final long mEncryptedDataStart;

    /**
     * Offset into file for the end of encrypted data (and, by extension,
     * authenticated data) in file.
     */
    private final long mDataEnd;

    public ContainerEncryptionParams(String encryptionAlgorithm,
            AlgorithmParameterSpec encryptionSpec, SecretKey encryptionKey)
            throws InvalidAlgorithmParameterException {
        this(encryptionAlgorithm, encryptionSpec, encryptionKey, null, null, null, null, -1, -1,
                -1);
    }

    /**
     * Creates container encryption specifications for installing from encrypted
     * containers.
     *
     * @param encryptionAlgorithm encryption algorithm to use; format matches
     *            JCE
     * @param encryptionSpec algorithm parameter specification
     * @param encryptionKey key used for decryption
     * @param macAlgorithm MAC algorithm to use; format matches JCE
     * @param macSpec algorithm parameters specification, may be {@code null}
     * @param macKey key used for authentication (i.e., for the MAC tag)
     * @param macTag message authentication code (MAC) tag for the authenticated
     *            data
     * @param authenticatedDataStart offset of start of authenticated data in
     *            stream
     * @param encryptedDataStart offset of start of encrypted data in stream
     * @param dataEnd offset of the end of both the authenticated and encrypted
     *            data
     * @throws InvalidAlgorithmParameterException
     */
    public ContainerEncryptionParams(String encryptionAlgorithm,
            AlgorithmParameterSpec encryptionSpec, SecretKey encryptionKey, String macAlgorithm,
            AlgorithmParameterSpec macSpec, SecretKey macKey, byte[] macTag,
            long authenticatedDataStart, long encryptedDataStart, long dataEnd)
            throws InvalidAlgorithmParameterException {
        if (TextUtils.isEmpty(encryptionAlgorithm)) {
            throw new NullPointerException("algorithm == null");
        } else if (encryptionSpec == null) {
            throw new NullPointerException("encryptionSpec == null");
        } else if (encryptionKey == null) {
            throw new NullPointerException("encryptionKey == null");
        }

        if (!TextUtils.isEmpty(macAlgorithm)) {
            if (macKey == null) {
                throw new NullPointerException("macKey == null");
            }
        }

        if (!(encryptionSpec instanceof IvParameterSpec)) {
            throw new InvalidAlgorithmParameterException(
                    "Unknown parameter spec class; must be IvParameters");
        }

        mEncryptionAlgorithm = encryptionAlgorithm;
        mEncryptionSpec = (IvParameterSpec) encryptionSpec;
        mEncryptionKey = encryptionKey;

        mMacAlgorithm = macAlgorithm;
        mMacSpec = macSpec;
        mMacKey = macKey;
        mMacTag = macTag;

        mAuthenticatedDataStart = authenticatedDataStart;
        mEncryptedDataStart = encryptedDataStart;
        mDataEnd = dataEnd;
    }

    public String getEncryptionAlgorithm() {
        return mEncryptionAlgorithm;
    }

    public AlgorithmParameterSpec getEncryptionSpec() {
        return mEncryptionSpec;
    }

    public SecretKey getEncryptionKey() {
        return mEncryptionKey;
    }

    public String getMacAlgorithm() {
        return mMacAlgorithm;
    }

    public AlgorithmParameterSpec getMacSpec() {
        return mMacSpec;
    }

    public SecretKey getMacKey() {
        return mMacKey;
    }

    public byte[] getMacTag() {
        return mMacTag;
    }

    public long getAuthenticatedDataStart() {
        return mAuthenticatedDataStart;
    }

    public long getEncryptedDataStart() {
        return mEncryptedDataStart;
    }

    public long getDataEnd() {
        return mDataEnd;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ContainerEncryptionParams)) {
            return false;
        }

        final ContainerEncryptionParams other = (ContainerEncryptionParams) o;

        // Primitive comparison
        if ((mAuthenticatedDataStart != other.mAuthenticatedDataStart)
                || (mEncryptedDataStart != other.mEncryptedDataStart)
                || (mDataEnd != other.mDataEnd)) {
            return false;
        }

        // String comparison
        if (!mEncryptionAlgorithm.equals(other.mEncryptionAlgorithm)
                || !mMacAlgorithm.equals(other.mMacAlgorithm)) {
            return false;
        }

        // Object comparison
        if (!isSecretKeyEqual(mEncryptionKey, other.mEncryptionKey)
                || !isSecretKeyEqual(mMacKey, other.mMacKey)) {
            return false;
        }

        if (!Arrays.equals(mEncryptionSpec.getIV(), other.mEncryptionSpec.getIV())
                || !Arrays.equals(mMacTag, other.mMacTag) || (mMacSpec != other.mMacSpec)) {
            return false;
        }

        return true;
    }

    private static final boolean isSecretKeyEqual(SecretKey key1, SecretKey key2) {
        final String keyFormat = key1.getFormat();
        final String otherKeyFormat = key2.getFormat();

        if (keyFormat == null) {
            if (keyFormat != otherKeyFormat) {
                return false;
            }

            if (key1.getEncoded() != key2.getEncoded()) {
                return false;
            }
        } else {
            if (!keyFormat.equals(key2.getFormat())) {
                return false;
            }

            if (!Arrays.equals(key1.getEncoded(), key2.getEncoded())) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int hash = 3;

        hash += 5 * mEncryptionAlgorithm.hashCode();
        hash += 7 * Arrays.hashCode(mEncryptionSpec.getIV());
        hash += 11 * mEncryptionKey.hashCode();
        hash += 13 * mMacAlgorithm.hashCode();
        hash += 17 * mMacKey.hashCode();
        hash += 19 * Arrays.hashCode(mMacTag);
        hash += 23 * mAuthenticatedDataStart;
        hash += 29 * mEncryptedDataStart;
        hash += 31 * mDataEnd;

        return hash;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(TO_STRING_PREFIX);

        sb.append("mEncryptionAlgorithm=\"");
        sb.append(mEncryptionAlgorithm);
        sb.append("\",");
        sb.append("mEncryptionSpec=");
        sb.append(mEncryptionSpec.toString());
        sb.append("mEncryptionKey=");
        sb.append(mEncryptionKey.toString());

        sb.append("mMacAlgorithm=\"");
        sb.append(mMacAlgorithm);
        sb.append("\",");
        sb.append("mMacSpec=");
        sb.append(mMacSpec.toString());
        sb.append("mMacKey=");
        sb.append(mMacKey.toString());

        sb.append(",mAuthenticatedDataStart=");
        sb.append(mAuthenticatedDataStart);
        sb.append(",mEncryptedDataStart=");
        sb.append(mEncryptedDataStart);
        sb.append(",mDataEnd=");
        sb.append(mDataEnd);
        sb.append('}');

        return sb.toString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mEncryptionAlgorithm);
        dest.writeInt(ENC_PARAMS_IV_PARAMETERS);
        dest.writeByteArray(mEncryptionSpec.getIV());
        dest.writeSerializable(mEncryptionKey);

        dest.writeString(mMacAlgorithm);
        dest.writeInt(MAC_PARAMS_NONE);
        dest.writeByteArray(new byte[0]);
        dest.writeSerializable(mMacKey);

        dest.writeByteArray(mMacTag);

        dest.writeLong(mAuthenticatedDataStart);
        dest.writeLong(mEncryptedDataStart);
        dest.writeLong(mDataEnd);
    }

    private ContainerEncryptionParams(Parcel source) throws InvalidAlgorithmParameterException {
        mEncryptionAlgorithm = source.readString();
        final int encParamType = source.readInt();
        final byte[] encParamsEncoded = source.createByteArray();
        mEncryptionKey = (SecretKey) source.readSerializable();

        mMacAlgorithm = source.readString();
        final int macParamType = source.readInt();
        source.createByteArray(); // byte[] macParamsEncoded
        mMacKey = (SecretKey) source.readSerializable();

        mMacTag = source.createByteArray();

        mAuthenticatedDataStart = source.readLong();
        mEncryptedDataStart = source.readLong();
        mDataEnd = source.readLong();

        switch (encParamType) {
            case ENC_PARAMS_IV_PARAMETERS:
                mEncryptionSpec = new IvParameterSpec(encParamsEncoded);
                break;
            default:
                throw new InvalidAlgorithmParameterException("Unknown parameter type "
                        + encParamType);
        }

        switch (macParamType) {
            case MAC_PARAMS_NONE:
                mMacSpec = null;
                break;
            default:
                throw new InvalidAlgorithmParameterException("Unknown parameter type "
                        + macParamType);
        }

        if (mEncryptionKey == null) {
            throw new NullPointerException("encryptionKey == null");
        }
    }

    public static final Parcelable.Creator<ContainerEncryptionParams> CREATOR =
            new Parcelable.Creator<ContainerEncryptionParams>() {
        public ContainerEncryptionParams createFromParcel(Parcel source) {
            try {
                return new ContainerEncryptionParams(source);
            } catch (InvalidAlgorithmParameterException e) {
                Slog.e(TAG, "Invalid algorithm parameters specified", e);
                return null;
            }
        }

        public ContainerEncryptionParams[] newArray(int size) {
            return new ContainerEncryptionParams[size];
        }
    };
}