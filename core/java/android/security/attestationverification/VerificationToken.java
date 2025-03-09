/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.security.attestationverification;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcelable;
import android.security.attestationverification.AttestationVerificationManager.LocalBindingType;
import android.security.attestationverification.AttestationVerificationManager.VerificationResultFlags;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForInstant;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Token representing the result of an attestation verification, which can be passed to other parts
 * of the OS or other apps as proof of the verification.
 *
 * Tokens are only valid within the same UID (which means within a single app unless the deprecated
 * android:sharedUserId manifest value is used).
 *
 * @hide
 * @see Bundle#putParcelable(String, Parcelable)
 */
public final class VerificationToken implements Parcelable {

    /**
     * The attestation profile which was used to perform the verification.
     * @hide
     */
    @NonNull
    private final AttestationProfile mAttestationProfile;

    /**
     * The local binding type of the local binding data used to perform the verification.
     * @hide
     */
    @LocalBindingType
    private final int mLocalBindingType;

    /**
     * The requirements used to perform the verification.
     * @hide
     */
    @NonNull
    private final Bundle mRequirements;

    /**
     * The result of the {@link AttestationVerificationManager#verifyAttestation(AttestationProfile,
     * int, Bundle, byte[], Executor, BiConsumer)} call. This value is kept hidden to prevent token
     * holders from accidentally reading this value without calling {@code verifyToken}. Do
     * <b>not</b> use this value directly; call {@link AttestationVerificationManager#verifyToken(
     * AttestationProfile, int, Bundle, VerificationToken, Duration)} to verify a valid token and it
     * will return this value.
     *
     * If the token is valid, this value is returned directly by {#verifyToken}.
     *
     * @hide
     */
    @VerificationResultFlags
    private final int mVerificationResult;

    /**
     * Time when the token was generated, set by the system.
     */
    @NonNull
    @DataClass.ParcelWith(ForInstant.class)
    private final java.time.Instant mVerificationTime;

    /**
     * A Hash-based message authentication code used to verify the contents and authenticity of the
     * rest of the token. The hash is created using a secret key known only to the system server.
     * When verifying the token, the system re-hashes the token and verifies the generated HMAC is
     * the same.
     *
     * @hide
     */
    @NonNull
    private final byte[] mHmac;

    /**
     * The UID of the process which called {@code verifyAttestation} to create the token, as
     * returned by {@link Binder#getCallingUid()}. Calls to {@code verifyToken} will fail if the UID
     * of calling process does not match this value. This ensures that tokens cannot be shared
     * between UIDs.
     *
     * @hide
     */
    private int mUid;


    VerificationToken(
            @NonNull AttestationProfile attestationProfile,
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @VerificationResultFlags int verificationResult,
            @NonNull java.time.Instant verificationTime,
            @NonNull byte[] hmac) {
        this.mAttestationProfile = attestationProfile;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAttestationProfile);
        this.mLocalBindingType = localBindingType;
        com.android.internal.util.AnnotationValidations.validate(
                LocalBindingType.class, null, mLocalBindingType);
        this.mRequirements = requirements;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mRequirements);
        this.mVerificationResult = verificationResult;
        com.android.internal.util.AnnotationValidations.validate(
                VerificationResultFlags.class, null, mVerificationResult);
        this.mVerificationTime = verificationTime;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mVerificationTime);
        this.mHmac = hmac;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHmac);
    }

    /**
     * The attestation profile which was used to perform the verification.
     *
     * @hide
     */
    public @NonNull AttestationProfile getAttestationProfile() {
        return mAttestationProfile;
    }

    /**
     * The local binding type of the local binding data used to perform the verification.
     *
     * @hide
     */
    public @LocalBindingType int getLocalBindingType() {
        return mLocalBindingType;
    }

    /**
     * The requirements used to perform the verification.
     *
     * @hide
     */
    public @NonNull Bundle getRequirements() {
        return mRequirements;
    }

    /**
     * The result of the {@link AttestationVerificationManager#verifyAttestation(AttestationProfile,
     * int, Bundle, byte[], Executor, BiConsumer)} call. This value is kept hidden to prevent token
     * holders from accidentally reading this value without calling {@code verifyToken}. Do
     * <b>not</b> use this value directly; call {@link AttestationVerificationManager#verifyToken(
     * AttestationProfile, int, Bundle, VerificationToken, Duration)} to verify a valid token and it
     * will return this value.
     *
     * If the token is valid, this value is returned directly by {#verifyToken}.
     *
     * @hide
     */
    public @VerificationResultFlags int getVerificationResult() {
        return mVerificationResult;
    }

    /**
     * Time when the token was generated, set by the system.
     */
    public @NonNull java.time.Instant getVerificationTime() {
        return mVerificationTime;
    }

    /**
     * A Hash-based message authentication code used to verify the contents and authenticity of the
     * rest of the token. The hash is created using a secret key known only to the system server.
     * When verifying the token, the system re-hashes the token and verifies the generated HMAC is
     * the same.
     *
     * @hide
     */
    public @NonNull byte[] getHmac() {
        return mHmac;
    }

    static Parcelling<java.time.Instant> sParcellingForVerificationTime =
            Parcelling.Cache.get(
                    ForInstant.class);
    static {
        if (sParcellingForVerificationTime == null) {
            sParcellingForVerificationTime = Parcelling.Cache.put(
                    new ForInstant());
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mAttestationProfile, flags);
        dest.writeInt(mLocalBindingType);
        dest.writeBundle(mRequirements);
        dest.writeInt(mVerificationResult);
        sParcellingForVerificationTime.parcel(mVerificationTime, dest, flags);
        dest.writeByteArray(mHmac);
    }

    @Override
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    VerificationToken(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        AttestationProfile attestationProfile = (AttestationProfile) in.readTypedObject(
                AttestationProfile.CREATOR);
        int localBindingType = in.readInt();
        Bundle requirements = in.readBundle();
        int verificationResult = in.readInt();
        java.time.Instant verificationTime = sParcellingForVerificationTime.unparcel(in);
        byte[] hmac = in.createByteArray();

        this.mAttestationProfile = attestationProfile;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAttestationProfile);
        this.mLocalBindingType = localBindingType;
        com.android.internal.util.AnnotationValidations.validate(
                LocalBindingType.class, null, mLocalBindingType);
        this.mRequirements = requirements;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mRequirements);
        this.mVerificationResult = verificationResult;
        com.android.internal.util.AnnotationValidations.validate(
                VerificationResultFlags.class, null, mVerificationResult);
        this.mVerificationTime = verificationTime;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mVerificationTime);
        this.mHmac = hmac;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHmac);
    }

    public static final @NonNull Parcelable.Creator<VerificationToken> CREATOR
            = new Parcelable.Creator<VerificationToken>() {
        @Override
        public VerificationToken[] newArray(int size) {
            return new VerificationToken[size];
        }

        @Override
        public VerificationToken createFromParcel(@NonNull android.os.Parcel in) {
            return new VerificationToken(in);
        }
    };

    /**
     * A builder for {@link VerificationToken}
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private @NonNull AttestationProfile mAttestationProfile;
        private @LocalBindingType int mLocalBindingType;
        private @NonNull Bundle mRequirements;
        private @VerificationResultFlags int mVerificationResult;
        private @NonNull java.time.Instant mVerificationTime;
        private @NonNull byte[] mHmac;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param attestationProfile
         *   The attestation profile which was used to perform the verification.
         * @param localBindingType
         *   The local binding type of the local binding data used to perform the verification.
         * @param requirements
         *   The requirements used to perform the verification.
         * @param verificationResult
         *   The result of the {@link AttestationVerificationManager#verifyAttestation(
         *   AttestationProfile, int, Bundle, byte[], Executor, BiConsumer)} call. This value is
         *   kept hidden to prevent token holders from accidentally reading this value without
         *   calling {@code verifyToken}. Do <b>not</b> use this value directly; call {@link
         *   AttestationVerificationManager#verifyToken(AttestationProfile, int, Bundle,
         *   VerificationToken, Duration)} to verify a valid token and it will return this value.
         *
         *   If the token is valid, this value is returned directly by {#verifyToken}.
         * @param verificationTime
         *   Time when the token was generated, set by the system.
         * @param hmac
         *   A Hash-based message authentication code used to verify the contents and authenticity
         *   of the rest of the token. The hash is created using a secret key known only to the
         *   system server. When verifying the token, the system re-hashes the token and verifies
         *   the generated HMAC is the same.
         */
        public Builder(
                @NonNull AttestationProfile attestationProfile,
                @LocalBindingType int localBindingType,
                @NonNull Bundle requirements,
                @VerificationResultFlags int verificationResult,
                @NonNull java.time.Instant verificationTime,
                @NonNull byte[] hmac) {
            mAttestationProfile = attestationProfile;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAttestationProfile);
            mLocalBindingType = localBindingType;
            com.android.internal.util.AnnotationValidations.validate(
                    LocalBindingType.class, null, mLocalBindingType);
            mRequirements = requirements;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mRequirements);
            mVerificationResult = verificationResult;
            com.android.internal.util.AnnotationValidations.validate(
                    VerificationResultFlags.class, null, mVerificationResult);
            mVerificationTime = verificationTime;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mVerificationTime);
            mHmac = hmac;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mHmac);
        }

        /**
         * The attestation profile which was used to perform the verification.
         *
         * @hide
         */
        public @NonNull Builder setAttestationProfile(@NonNull AttestationProfile value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mAttestationProfile = value;
            return this;
        }

        /**
         * The local binding type of the local binding data used to perform the verification.
         *
         * @hide
         */
        public @NonNull Builder setLocalBindingType(@LocalBindingType int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mLocalBindingType = value;
            return this;
        }

        /**
         * The requirements used to perform the verification.
         *
         * @hide
         */
        public @NonNull Builder setRequirements(@NonNull Bundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mRequirements = value;
            return this;
        }

        /**
         * The result of the {@link AttestationVerificationManager#verifyAttestation(
         * AttestationProfile, int, Bundle, byte[], Executor, BiConsumer)} call. This value is kept
         * hidden to prevent token holders from accidentally reading this value without calling
         * {@code verifyToken}. Do <b>not</b> use this value directly; call {@link
         * AttestationVerificationManager#verifyToken(AttestationProfile, int, Bundle,
         * VerificationToken, Duration)} to verify a valid token and it will return this value.
         *
         * If the token is valid, this value is returned directly by {#verifyToken}.
         *
         * @hide
         */
        public @NonNull Builder setVerificationResult(@VerificationResultFlags int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mVerificationResult = value;
            return this;
        }

        /**
         * Time when the token was generated, set by the system.
         */
        public @NonNull Builder setVerificationTime(@NonNull java.time.Instant value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mVerificationTime = value;
            return this;
        }

        /**
         * A Hash-based message authentication code used to verify the contents and authenticity of
         * the rest of the token. The hash is created using a secret key known only to the system
         * server. When verifying the token, the system re-hashes the token and verifies the
         * generated HMAC is the same.
         *
         * @hide
         */
        public @NonNull Builder setHmac(@NonNull byte... value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mHmac = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull VerificationToken build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40; // Mark builder used

            VerificationToken o = new VerificationToken(
                    mAttestationProfile,
                    mLocalBindingType,
                    mRequirements,
                    mVerificationResult,
                    mVerificationTime,
                    mHmac);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x40) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
