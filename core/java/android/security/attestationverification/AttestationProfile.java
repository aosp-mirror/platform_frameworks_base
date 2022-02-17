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

import static android.security.attestationverification.AttestationVerificationManager.PROFILE_APP_DEFINED;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcelable;
import android.security.attestationverification.AttestationVerificationManager.AttestationProfileId;
import android.util.Log;

import com.android.internal.util.DataClass;


/**
 * An attestation profile defining the security requirements for verifying the attestation of a
 * remote compute environment.
 *
 * <p>This class is immutable and thread-safe. When checking this profile against an expected
 * profile, it is recommended to construct the expected profile and compare them with {@code
 * equals()}.
 *
 * @hide
 * @see AttestationVerificationManager
 */
@DataClass(
        genConstructor = false,
        genEqualsHashCode = true
)
public final class AttestationProfile implements Parcelable {

    private static final String TAG = "AVF";

    /**
     * The ID of a system-defined attestation profile.
     *
     * See constants in {@link AttestationVerificationManager} prefixed with {@code PROFILE_}. If
     * this has the value of {@link AttestationVerificationManager#PROFILE_APP_DEFINED}, then the
     * packageName and profileName are non-null.
     */
    @AttestationProfileId
    private final int mAttestationProfileId;

    /**
     * The package name of a app-defined attestation profile.
     *
     * This value will be null unless the value of attestationProfileId is {@link
     * AttestationVerificationManager#PROFILE_APP_DEFINED}.
     */
    @Nullable
    private final String mPackageName;


    /**
     * The name of an app-defined attestation profile.
     *
     * This value will be null unless the value of attestationProfileId is {@link
     * AttestationVerificationManager#PROFILE_APP_DEFINED}.
     */
    @Nullable
    private final String mProfileName;

    private AttestationProfile(
            @AttestationProfileId int attestationProfileId,
            @Nullable String packageName,
            @Nullable String profileName) {
        mAttestationProfileId = attestationProfileId;
        mPackageName = packageName;
        mProfileName = profileName;
    }

    /**
     * Create a profile with the given id.
     *
     * <p>This constructor is for specifying a profile which is defined by the system. These are
     * available as constants in the {@link AttestationVerificationManager} class prefixed with
     * {@code PROFILE_}.
     *
     * @param attestationProfileId the ID of the system-defined profile
     * @throws IllegalArgumentException when called with
     * {@link AttestationVerificationManager#PROFILE_APP_DEFINED}
     *                                  (use {@link #AttestationProfile(String, String)})
     */
    public AttestationProfile(@AttestationProfileId int attestationProfileId) {
        this(attestationProfileId, null, null);
        if (attestationProfileId == PROFILE_APP_DEFINED) {
            throw new IllegalArgumentException("App-defined profiles must be specified with the "
                    + "constructor AttestationProfile#constructor(String, String)");
        }
    }

    /**
     * Create a profile with the given package name and profile name.
     *
     * <p>This constructor is for specifying a profile defined by an app. The packageName must
     * match the package name of the app that defines the profile (as specified in the {@code
     * package} attribute of the {@code
     * <manifest>} tag in the app's manifest. The profile name matches the {@code name} attribute
     * of the {@code <attestation-profile>} tag.
     *
     * <p>Apps must declare profiles in their manifest as an {@code <attestation-profile>} element.
     * However, this constructor does not verify that such a profile exists. If the profile does not
     * exist, verifications will fail.
     *
     * @param packageName the package name of the app defining the profile
     * @param profileName the name of the profile
     */
    public AttestationProfile(@NonNull String packageName, @NonNull String profileName) {
        this(PROFILE_APP_DEFINED, packageName, profileName);
        if (packageName == null || profileName == null) {
            throw new IllegalArgumentException("Both packageName and profileName must be non-null");
        }
    }

    @Override
    public String toString() {
        if (mAttestationProfileId == PROFILE_APP_DEFINED) {
            return "AttestationProfile(package=" + mPackageName + ", name=" + mProfileName + ")";
        } else {
            String humanReadableProfileId;
            switch (mAttestationProfileId) {
                case PROFILE_UNKNOWN:
                    humanReadableProfileId = "PROFILE_UNKNOWN";
                    break;
                default:
                    Log.e(TAG, "ERROR: Missing case in AttestationProfile#toString");
                    humanReadableProfileId = "ERROR";
            }
            return "AttestationProfile(" + humanReadableProfileId + "/" + mAttestationProfileId
                    + ")";
        }
    }


    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/security
    // /attestationverification/AttestationProfile.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * The ID of a system-defined attestation profile.
     *
     * See constants in {@link AttestationVerificationManager} prefixed with {@code PROFILE_}. If
     * this has the value of {@link AttestationVerificationManager#PROFILE_APP_DEFINED}, then the
     * packageName and profileName are non-null.
     */
    @DataClass.Generated.Member
    public @AttestationProfileId int getAttestationProfileId() {
        return mAttestationProfileId;
    }

    /**
     * The package name of a app-defined attestation profile.
     *
     * This value will be null unless the value of attestationProfileId is {@link
     * AttestationVerificationManager#PROFILE_APP_DEFINED}.
     */
    @DataClass.Generated.Member
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * The name of an app-defined attestation profile.
     *
     * This value will be null unless the value of attestationProfileId is {@link
     * AttestationVerificationManager#PROFILE_APP_DEFINED}.
     */
    @DataClass.Generated.Member
    public @Nullable String getProfileName() {
        return mProfileName;
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(AttestationProfile other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        AttestationProfile that = (AttestationProfile) o;
        //noinspection PointlessBooleanExpression
        return true
                && mAttestationProfileId == that.mAttestationProfileId
                && java.util.Objects.equals(mPackageName, that.mPackageName)
                && java.util.Objects.equals(mProfileName, that.mProfileName);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mAttestationProfileId;
        _hash = 31 * _hash + java.util.Objects.hashCode(mPackageName);
        _hash = 31 * _hash + java.util.Objects.hashCode(mProfileName);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mPackageName != null) flg |= 0x2;
        if (mProfileName != null) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeInt(mAttestationProfileId);
        if (mPackageName != null) dest.writeString(mPackageName);
        if (mProfileName != null) dest.writeString(mProfileName);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ AttestationProfile(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int attestationProfileId = in.readInt();
        String packageName = (flg & 0x2) == 0 ? null : in.readString();
        String profileName = (flg & 0x4) == 0 ? null : in.readString();

        this.mAttestationProfileId = attestationProfileId;
        com.android.internal.util.AnnotationValidations.validate(
                AttestationProfileId.class, null, mAttestationProfileId);
        this.mPackageName = packageName;
        this.mProfileName = profileName;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<AttestationProfile> CREATOR
            = new Parcelable.Creator<AttestationProfile>() {
        @Override
        public AttestationProfile[] newArray(int size) {
            return new AttestationProfile[size];
        }

        @Override
        public AttestationProfile createFromParcel(@NonNull android.os.Parcel in) {
            return new AttestationProfile(in);
        }
    };

    @DataClass.Generated(
            time = 1633629498403L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/security/attestationverification/AttestationProfile.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.security.attestationverification.AttestationVerificationManager.AttestationProfileId int mAttestationProfileId\nprivate final @android.annotation.Nullable java.lang.String mPackageName\nprivate final @android.annotation.Nullable java.lang.String mProfileName\npublic @java.lang.Override java.lang.String toString()\nclass AttestationProfile extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
