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

package android.security.keystore;

import android.os.Parcelable;
import android.os.Parcel;

import java.math.BigInteger;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * A parcelable version of KeyGenParameterSpec
 * @hide only used for communicating with the DPMS.
 */
public final class ParcelableKeyGenParameterSpec implements Parcelable {
    private static final int ALGORITHM_PARAMETER_SPEC_NONE = 1;
    private static final int ALGORITHM_PARAMETER_SPEC_RSA = 2;
    private static final int ALGORITHM_PARAMETER_SPEC_EC = 3;

    private final KeyGenParameterSpec mSpec;

    public ParcelableKeyGenParameterSpec(
            KeyGenParameterSpec spec) {
        mSpec = spec;
    }

    public int describeContents() {
        return 0;
    }

    private static void writeOptionalDate(Parcel out, Date date) {
        if (date != null) {
            out.writeBoolean(true);
            out.writeLong(date.getTime());
        } else {
            out.writeBoolean(false);
        }
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mSpec.getKeystoreAlias());
        out.writeInt(mSpec.getPurposes());
        out.writeInt(mSpec.getUid());
        out.writeInt(mSpec.getKeySize());

        // Only needs to support RSAKeyGenParameterSpec and ECGenParameterSpec.
        AlgorithmParameterSpec algoSpec = mSpec.getAlgorithmParameterSpec();
        if (algoSpec == null) {
            out.writeInt(ALGORITHM_PARAMETER_SPEC_NONE);
        } else if (algoSpec instanceof RSAKeyGenParameterSpec) {
            RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) algoSpec;
            out.writeInt(ALGORITHM_PARAMETER_SPEC_RSA);
            out.writeInt(rsaSpec.getKeysize());
            out.writeByteArray(rsaSpec.getPublicExponent().toByteArray());
        } else if (algoSpec instanceof ECGenParameterSpec) {
            ECGenParameterSpec ecSpec = (ECGenParameterSpec) algoSpec;
            out.writeInt(ALGORITHM_PARAMETER_SPEC_EC);
            out.writeString(ecSpec.getName());
        } else {
            throw new IllegalArgumentException(
                    String.format("Unknown algorithm parameter spec: %s", algoSpec.getClass()));
        }
        out.writeByteArray(mSpec.getCertificateSubject().getEncoded());
        out.writeByteArray(mSpec.getCertificateSerialNumber().toByteArray());
        out.writeLong(mSpec.getCertificateNotBefore().getTime());
        out.writeLong(mSpec.getCertificateNotAfter().getTime());
        writeOptionalDate(out, mSpec.getKeyValidityStart());
        writeOptionalDate(out, mSpec.getKeyValidityForOriginationEnd());
        writeOptionalDate(out, mSpec.getKeyValidityForConsumptionEnd());
        if (mSpec.isDigestsSpecified()) {
            out.writeStringArray(mSpec.getDigests());
        } else {
            out.writeStringArray(null);
        }
        out.writeStringArray(mSpec.getEncryptionPaddings());
        out.writeStringArray(mSpec.getSignaturePaddings());
        out.writeStringArray(mSpec.getBlockModes());
        out.writeBoolean(mSpec.isRandomizedEncryptionRequired());
        out.writeBoolean(mSpec.isUserAuthenticationRequired());
        out.writeInt(mSpec.getUserAuthenticationValidityDurationSeconds());
        out.writeByteArray(mSpec.getAttestationChallenge());
        out.writeBoolean(mSpec.isUniqueIdIncluded());
        out.writeBoolean(mSpec.isUserAuthenticationValidWhileOnBody());
        out.writeBoolean(mSpec.isInvalidatedByBiometricEnrollment());
        out.writeBoolean(mSpec.isUserPresenceRequired());
    }

    private static Date readDateOrNull(Parcel in) {
        boolean hasDate = in.readBoolean();
        if (hasDate) {
            return new Date(in.readLong());
        } else {
            return null;
        }
    }

    private ParcelableKeyGenParameterSpec(Parcel in) {
        String keystoreAlias = in.readString();
        int purposes = in.readInt();
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                keystoreAlias, purposes);
        builder.setUid(in.readInt());
        // KeySize is -1 by default, if the KeyGenParameterSpec previously parcelled had the default
        // value, do not set it as this will cause setKeySize to throw.
        int keySize = in.readInt();
        if (keySize >= 0) {
            builder.setKeySize(keySize);
        }

        int keySpecType = in.readInt();
        AlgorithmParameterSpec algorithmSpec = null;
        if (keySpecType == ALGORITHM_PARAMETER_SPEC_NONE) {
            algorithmSpec = null;
        } else if (keySpecType == ALGORITHM_PARAMETER_SPEC_RSA) {
            int rsaKeySize = in.readInt();
            BigInteger publicExponent = new BigInteger(in.createByteArray());
            algorithmSpec = new RSAKeyGenParameterSpec(rsaKeySize, publicExponent);
        } else if (keySpecType == ALGORITHM_PARAMETER_SPEC_EC) {
            String stdName = in.readString();
            algorithmSpec = new ECGenParameterSpec(stdName);
        } else {
            throw new IllegalArgumentException(
                    String.format("Unknown algorithm parameter spec: %d", keySpecType));
        }
        if (algorithmSpec != null) {
            builder.setAlgorithmParameterSpec(algorithmSpec);
        }
        builder.setCertificateSubject(new X500Principal(in.createByteArray()));
        builder.setCertificateSerialNumber(new BigInteger(in.createByteArray()));
        builder.setCertificateNotBefore(new Date(in.readLong()));
        builder.setCertificateNotAfter(new Date(in.readLong()));
        builder.setKeyValidityStart(readDateOrNull(in));
        builder.setKeyValidityForOriginationEnd(readDateOrNull(in));
        builder.setKeyValidityForConsumptionEnd(readDateOrNull(in));
        String[] digests = in.createStringArray();
        if (digests != null) {
            builder.setDigests(digests);
        }
        builder.setEncryptionPaddings(in.createStringArray());
        builder.setSignaturePaddings(in.createStringArray());
        builder.setBlockModes(in.createStringArray());
        builder.setRandomizedEncryptionRequired(in.readBoolean());
        builder.setUserAuthenticationRequired(in.readBoolean());
        builder.setUserAuthenticationValidityDurationSeconds(in.readInt());
        builder.setAttestationChallenge(in.createByteArray());
        builder.setUniqueIdIncluded(in.readBoolean());
        builder.setUserAuthenticationValidWhileOnBody(in.readBoolean());
        builder.setInvalidatedByBiometricEnrollment(in.readBoolean());
        builder.setUserPresenceRequired(in.readBoolean());
        mSpec = builder.build();
    }

    public static final Creator<ParcelableKeyGenParameterSpec> CREATOR = new Creator<ParcelableKeyGenParameterSpec>() {
        @Override
        public ParcelableKeyGenParameterSpec createFromParcel(Parcel in) {
            return new ParcelableKeyGenParameterSpec(in);
        }

        @Override
        public ParcelableKeyGenParameterSpec[] newArray(int size) {
            return new ParcelableKeyGenParameterSpec[size];
        }
    };

    public KeyGenParameterSpec getSpec() {
        return mSpec;
    }
}
