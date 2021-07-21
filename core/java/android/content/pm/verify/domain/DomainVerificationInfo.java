/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contains the state of all domains for a given package on device. Used by the domain verification
 * agent to determine the domains declared by a package that need to be verified by comparing
 * against the digital asset links response from the server hosting that domain.
 * <p>
 * These values for each domain can be modified through
 * {@link DomainVerificationManager#setDomainVerificationStatus(UUID,
 * Set, int)}.
 *
 * @hide
 */
@SystemApi
@DataClass(genAidl = true, genHiddenConstructor = true, genParcelable = true, genToString = true,
        genEqualsHashCode = true, genHiddenConstDefs = true)
public final class DomainVerificationInfo implements Parcelable {

    // Implementation note: the following states are OUTPUT only. Any value that is synonymous with
    // a value in DomainVerificationState must be the EXACT same integer, so that state
    // transformation does not have to occur when sending input into the system, assuming that the
    // system only accepts those synonymous values. The public API values declared here are only
    // used when exiting the system server to prepare this data object for consumption by the
    // verification agent. These constants should only be referenced inside public API classes.
    // The server must use DomainVerificationState.

    /**
     * No response has been recorded by either the system or any verification agent.
     */
    public static final int STATE_NO_RESPONSE = DomainVerificationState.STATE_NO_RESPONSE;

    /**
     * The domain has been explicitly verified.
     */
    public static final int STATE_SUCCESS = DomainVerificationState.STATE_SUCCESS;

    /**
     * Indicates the host cannot be modified by the verification agent.
     */
    public static final int STATE_UNMODIFIABLE = 2;

    /**
     * Indicates the host can be modified by the verification agent and is not considered verified.
     */
    public static final int STATE_MODIFIABLE_UNVERIFIED = 3;

    /**
     * Indicates the host can be modified by the verification agent and is considered verified.
     */
    public static final int STATE_MODIFIABLE_VERIFIED = 4;

    /**
     * The first available custom response code. This and any greater integer, along with {@link
     * #STATE_SUCCESS} are the only values settable by the verification agent. All custom values
     * will be treated as if the domain is unverified.
     */
    public static final int STATE_FIRST_VERIFIER_DEFINED =
            DomainVerificationState.STATE_FIRST_VERIFIER_DEFINED;

    /**
     * A domain verification ID for use in later API calls. This represents the snapshot of the
     * domains for a package on device, and will be invalidated whenever the package changes.
     * <p>
     * An exception will be thrown at the next API call that receives the ID if it is no longer
     * valid.
     * <p>
     * The caller may also be notified with a broadcast whenever a package and ID is invalidated, at
     * which point it can use the package name to evict existing requests with an invalid set ID. If
     * the caller wants to manually check if any IDs have been invalidate, the {@link
     * PackageManager#getChangedPackages(int)} API will allow tracking the packages changed since
     * the last query of this method, prompting the caller to re-query.
     * <p>
     * This allows the caller to arbitrarily grant or revoke domain verification status, through
     * {@link DomainVerificationManager#setDomainVerificationStatus(UUID, Set, int)}.
     */
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForUUID.class)
    private final UUID mIdentifier;

    /**
     * The package name that this data corresponds to.
     */
    @NonNull
    private final String mPackageName;

    /**
     * Map of host names to their current state. State is an integer, which defaults to {@link
     * #STATE_NO_RESPONSE}. State can be modified by the domain verification agent (the intended
     * consumer of this API), which can be equal to {@link #STATE_SUCCESS} when verified, or equal
     * to or greater than {@link #STATE_FIRST_VERIFIER_DEFINED} for any unsuccessful response.
     * <p>
     * Hosts which cannot be edited will be assigned {@link #STATE_UNMODIFIABLE}. It is expected
     * that the agent attempt to verify all domains that it can modify the state of.
     */
    @NonNull
    private final Map<String, Integer> mHostToStateMap;

    private void parcelHostToStateMap(Parcel dest, @SuppressWarnings("unused") int flags) {
        DomainVerificationUtils.writeHostMap(dest, mHostToStateMap);
    }

    private Map<String, Integer> unparcelHostToStateMap(Parcel in) {
        return DomainVerificationUtils.readHostMap(in, new ArrayMap<>(),
                DomainVerificationUserState.class.getClassLoader());
    }



    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/verify/domain/DomainVerificationInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @android.annotation.IntDef(prefix = "STATE_", value = {
        STATE_NO_RESPONSE,
        STATE_SUCCESS,
        STATE_UNMODIFIABLE,
        STATE_MODIFIABLE_UNVERIFIED,
        STATE_MODIFIABLE_VERIFIED,
        STATE_FIRST_VERIFIER_DEFINED
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface State {}

    /** @hide */
    @DataClass.Generated.Member
    public static String stateToString(@State int value) {
        switch (value) {
            case STATE_NO_RESPONSE:
                    return "STATE_NO_RESPONSE";
            case STATE_SUCCESS:
                    return "STATE_SUCCESS";
            case STATE_UNMODIFIABLE:
                    return "STATE_UNMODIFIABLE";
            case STATE_MODIFIABLE_UNVERIFIED:
                    return "STATE_MODIFIABLE_UNVERIFIED";
            case STATE_MODIFIABLE_VERIFIED:
                    return "STATE_MODIFIABLE_VERIFIED";
            case STATE_FIRST_VERIFIER_DEFINED:
                    return "STATE_FIRST_VERIFIER_DEFINED";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new DomainVerificationInfo.
     *
     * @param identifier
     *   A domain verification ID for use in later API calls. This represents the snapshot of the
     *   domains for a package on device, and will be invalidated whenever the package changes.
     *   <p>
     *   An exception will be thrown at the next API call that receives the ID if it is no longer
     *   valid.
     *   <p>
     *   The caller may also be notified with a broadcast whenever a package and ID is invalidated, at
     *   which point it can use the package name to evict existing requests with an invalid set ID. If
     *   the caller wants to manually check if any IDs have been invalidate, the {@link
     *   PackageManager#getChangedPackages(int)} API will allow tracking the packages changed since
     *   the last query of this method, prompting the caller to re-query.
     *   <p>
     *   This allows the caller to arbitrarily grant or revoke domain verification status, through
     *   {@link DomainVerificationManager#setDomainVerificationStatus(UUID, Set, int)}.
     * @param packageName
     *   The package name that this data corresponds to.
     * @param hostToStateMap
     *   Map of host names to their current state. State is an integer, which defaults to {@link
     *   #STATE_NO_RESPONSE}. State can be modified by the domain verification agent (the intended
     *   consumer of this API), which can be equal to {@link #STATE_SUCCESS} when verified, or equal
     *   to or greater than {@link #STATE_FIRST_VERIFIER_DEFINED} for any unsuccessful response.
     *   <p>
     *   Hosts which cannot be edited will be assigned {@link #STATE_UNMODIFIABLE}. It is expected
     *   that the agent attempt to verify all domains that it can modify the state of.
     * @hide
     */
    @DataClass.Generated.Member
    public DomainVerificationInfo(
            @NonNull UUID identifier,
            @NonNull String packageName,
            @NonNull Map<String,Integer> hostToStateMap) {
        this.mIdentifier = identifier;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mIdentifier);
        this.mPackageName = packageName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
        this.mHostToStateMap = hostToStateMap;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHostToStateMap);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * A domain verification ID for use in later API calls. This represents the snapshot of the
     * domains for a package on device, and will be invalidated whenever the package changes.
     * <p>
     * An exception will be thrown at the next API call that receives the ID if it is no longer
     * valid.
     * <p>
     * The caller may also be notified with a broadcast whenever a package and ID is invalidated, at
     * which point it can use the package name to evict existing requests with an invalid set ID. If
     * the caller wants to manually check if any IDs have been invalidate, the {@link
     * PackageManager#getChangedPackages(int)} API will allow tracking the packages changed since
     * the last query of this method, prompting the caller to re-query.
     * <p>
     * This allows the caller to arbitrarily grant or revoke domain verification status, through
     * {@link DomainVerificationManager#setDomainVerificationStatus(UUID, Set, int)}.
     */
    @DataClass.Generated.Member
    public @NonNull UUID getIdentifier() {
        return mIdentifier;
    }

    /**
     * The package name that this data corresponds to.
     */
    @DataClass.Generated.Member
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * Map of host names to their current state. State is an integer, which defaults to {@link
     * #STATE_NO_RESPONSE}. State can be modified by the domain verification agent (the intended
     * consumer of this API), which can be equal to {@link #STATE_SUCCESS} when verified, or equal
     * to or greater than {@link #STATE_FIRST_VERIFIER_DEFINED} for any unsuccessful response.
     * <p>
     * Hosts which cannot be edited will be assigned {@link #STATE_UNMODIFIABLE}. It is expected
     * that the agent attempt to verify all domains that it can modify the state of.
     */
    @DataClass.Generated.Member
    public @NonNull Map<String,Integer> getHostToStateMap() {
        return mHostToStateMap;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "DomainVerificationInfo { " +
                "identifier = " + mIdentifier + ", " +
                "packageName = " + mPackageName + ", " +
                "hostToStateMap = " + mHostToStateMap +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@android.annotation.Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(DomainVerificationInfo other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        DomainVerificationInfo that = (DomainVerificationInfo) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Objects.equals(mIdentifier, that.mIdentifier)
                && java.util.Objects.equals(mPackageName, that.mPackageName)
                && java.util.Objects.equals(mHostToStateMap, that.mHostToStateMap);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Objects.hashCode(mIdentifier);
        _hash = 31 * _hash + java.util.Objects.hashCode(mPackageName);
        _hash = 31 * _hash + java.util.Objects.hashCode(mHostToStateMap);
        return _hash;
    }

    @DataClass.Generated.Member
    static Parcelling<UUID> sParcellingForIdentifier =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForUUID.class);
    static {
        if (sParcellingForIdentifier == null) {
            sParcellingForIdentifier = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForUUID());
        }
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        sParcellingForIdentifier.parcel(mIdentifier, dest, flags);
        dest.writeString(mPackageName);
        parcelHostToStateMap(dest, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ DomainVerificationInfo(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        UUID identifier = sParcellingForIdentifier.unparcel(in);
        String packageName = in.readString();
        Map<String,Integer> hostToStateMap = unparcelHostToStateMap(in);

        this.mIdentifier = identifier;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mIdentifier);
        this.mPackageName = packageName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
        this.mHostToStateMap = hostToStateMap;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHostToStateMap);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<DomainVerificationInfo> CREATOR
            = new Parcelable.Creator<DomainVerificationInfo>() {
        @Override
        public DomainVerificationInfo[] newArray(int size) {
            return new DomainVerificationInfo[size];
        }

        @Override
        public DomainVerificationInfo createFromParcel(@NonNull Parcel in) {
            return new DomainVerificationInfo(in);
        }
    };

    @DataClass.Generated(
            time = 1615317187669L,
            codegenVersion = "1.0.22",
            sourceFile = "frameworks/base/core/java/android/content/pm/verify/domain/DomainVerificationInfo.java",
            inputSignatures = "public static final  int STATE_NO_RESPONSE\npublic static final  int STATE_SUCCESS\npublic static final  int STATE_UNMODIFIABLE\npublic static final  int STATE_MODIFIABLE_UNVERIFIED\npublic static final  int STATE_MODIFIABLE_VERIFIED\npublic static final  int STATE_FIRST_VERIFIER_DEFINED\nprivate final @android.annotation.NonNull @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForUUID.class) java.util.UUID mIdentifier\nprivate final @android.annotation.NonNull java.lang.String mPackageName\nprivate final @android.annotation.NonNull java.util.Map<java.lang.String,java.lang.Integer> mHostToStateMap\nprivate  void parcelHostToStateMap(android.os.Parcel,int)\nprivate  java.util.Map<java.lang.String,java.lang.Integer> unparcelHostToStateMap(android.os.Parcel)\nclass DomainVerificationInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genAidl=true, genHiddenConstructor=true, genParcelable=true, genToString=true, genEqualsHashCode=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
