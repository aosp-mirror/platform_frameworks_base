/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.assist.classification;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.autofill.AutofillId;


import com.android.internal.util.DataClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Represents a classified field from the detection service.
 */
// TODO(b/266930067): Once @SystemApi is supported, use genSetters and genConstructor.
@DataClass(
        genToString = true,
        genConstructor = false
)
public final class FieldClassification implements Parcelable {

    /**
     * Autofill id of the detected field
     */
    private final @NonNull AutofillId mAutofillId;

    /**
     * Detected fields types represented as autofill hints
     *
     * A particular field can be detected as multiple types. For eg: A sign-in field may take in a
     * username, an email address or a phone number. In such cases, it should be detected as
     * "username", "emailAddress" and "phoneNumber"
     *
     * The value of these hints are contained in androidx.autofill.HintConstants
     */
    private final @NonNull Set<String> mHints;


    /**
     * Group hints are the hints that may represent the group of related hints (including
     * themselves). The value of these group hints are contained in androidx.autofill.HintConstants
     *
     * <p>
     *
     * "creditCardNumber" is the group hint for hints containing credit card related fields:
     * "creditCardNumber", "creditCardExpirationDate", "creditCardExpirationDay",
     * "creditCardExpirationMonth", "creditCardExpirationYear", "creditCardSecurityCode",
     *
     * <p>
     *
     * "postalAddress" is the group hint for hints all postal address related fields:
     * "postalAddress", "streetAddress", "aptNumber", "dependentLocality", "extendedAddress",
     * "postalCode", "extendedPostalCode", "addressLocality", "addressRegion", "addressCountry".
     *
     * <p>
     *
     * "phoneNumber" is the group hint for hints all phone number related fields: "phoneNumber",
     * "phoneNumberDevice", "phoneNational", "phoneCountryCode".
     *
     * <p>
     *
     * "personName" is the group hint for hints all name related fields: "personName",
     * "personFamilyName", "personGivenName", "personMiddleName", "personMiddleInitial",
     * "personNamePrefix", "personNameSuffix" .
     *
     * <p>
     *
     * "birthDateFull" is the group hint for hints containing birthday related fields:
     * "birthDateFull", "birthDateMonth", "birthDateYear",
     *
     * @hide
     */
    private final @NonNull Set<String> mGroupHints;

    /**
     * Autofill id of the detected field.
     */
    public @NonNull AutofillId getAutofillId() {
        return mAutofillId;
    }

    /**
     * Detected fields types represented as autofill hints.
     *
     * A particular field can be detected as multiple types. For eg: A sign-in field may take in a
     * username, an email address or a phone number. In such cases, it should be detected as
     * "username", "emailAddress" and "phoneNumber"
     *
     * The value of these hints are contained in androidx.autofill.HintConstants
     */
    public @NonNull Set<String> getHints() {
        return mHints;
    }

    /**
    * Group hints are the hints that may represent the group of related hints (including
    * themselves). The value of these group hints are contained in androidx.autofill.HintConstants
    *
    * <p>
    *
    * "creditCardNumber" is the group hint for hints containing credit card related fields:
    * "creditCardNumber", "creditCardExpirationDate", "creditCardExpirationDay",
    * "creditCardExpirationMonth", "creditCardExpirationYear", "creditCardSecurityCode",
    *
    * <p>
    *
    * "postalAddress" is the group hint for hints all postal address related fields:
    * "postalAddress", "streetAddress", "aptNumber", "dependentLocality", "extendedAddress",
    * "postalCode", "extendedPostalCode", "addressLocality", "addressRegion", "addressCountry".
    *
    * <p>
    *
    * "phoneNumber" is the group hint for hints all phone number related fields: "phoneNumber",
    * "phoneNumberDevice", "phoneNational", "phoneCountryCode".
    *
    * <p>
    *
    * "personName" is the group hint for hints all name related fields: "personName",
    * "personFamilyName", "personGivenName", "personMiddleName", "personMiddleInitial",
    * "personNamePrefix", "personNameSuffix" .
    *
    * <p>
    *
    * "birthDateFull" is the group hint for hints containing birthday related fields:
    * "birthDateFull", "birthDateMonth", "birthDateYear",
    *
    * @hide
    */
    @SystemApi
    public @NonNull Set<String> getGroupHints() {
        return mGroupHints;
    }

    static Set<String> unparcelHints(Parcel in) {
        List<String> hints = new java.util.ArrayList<>();
        in.readStringList(hints);
        return new ArraySet<>(hints);
    }

    void parcelHints(Parcel dest, int flags) {
        dest.writeStringList(new ArrayList<>(mHints));
    }

    static Set<String> unparcelGroupHints(Parcel in) {
        List<String> groupHints = new java.util.ArrayList<>();
        in.readStringList(groupHints);
        return new ArraySet<>(groupHints);
    }

    void parcelGroupHints(Parcel dest, int flags) {
        dest.writeStringList(new ArrayList<>(mGroupHints));
    }

    /**
     * Creates a new FieldClassification.
     *
     * @param autofillId
     *   Autofill id of the detected field
     * @param hints
     *   Detected fields types represented as autofill hints.
     *   A particular field can be detected as multiple types. For eg: A sign-in field may take in
     *   a username, an email address or a phone number. In such cases, it should be detected as
     *   "username", "emailAddress" and "phoneNumber"
     */
    public FieldClassification(
            @NonNull AutofillId autofillId,
            @NonNull Set<String> hints) {
        this(autofillId, hints, new ArraySet<>());
    }

    /**
    * Creates a new FieldClassification.
    *
    * @param autofillId Autofill id of the detected field
    * @param hints Detected fields types represented as autofill hints A particular field can be
    *     detected as multiple types. For eg: A sign-in field may take in a username, an email
    *     address or a phone number. In such cases, it should be detected as "username",
    *     "emailAddress" and "phoneNumber"
    * @param groupHints Hints that may represent the group of related hints (including themselves).
    *     The value of these group hints are contained in androidx.autofill.HintConstants.
     *    See {@link #getGroupHints()} for more details
    * @hide
    */
    @SystemApi
    @DataClass.Generated.Member
    public FieldClassification(
            @NonNull AutofillId autofillId,
            @NonNull Set<String> hints,
            @NonNull Set<String> groupHints) {
        this.mAutofillId = autofillId;
//        com.android.internal.util.AnnotationValidations.validate(
//                NonNull.class, null, mAutofillId);
        this.mHints = hints;
//        com.android.internal.util.AnnotationValidations.validate(
//                NonNull.class, null, mHints);
        this.mGroupHints = groupHints;
//        com.android.internal.util.AnnotationValidations.validate(
//                NonNull.class, null, mGroupHints);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/service/assist/classification/FieldClassification.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "FieldClassification { " +
                "autofillId = " + mAutofillId + ", " +
                "hints = " + mHints + ", " +
                "groupHints = " + mGroupHints +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mAutofillId, flags);
        parcelHints(dest, flags);
        parcelGroupHints(dest, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ FieldClassification(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        AutofillId autofillId = (AutofillId) in.readTypedObject(AutofillId.CREATOR);
        Set<String> hints = unparcelHints(in);
        Set<String> groupHints = unparcelGroupHints(in);

        this.mAutofillId = autofillId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAutofillId);
        this.mHints = hints;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHints);
        this.mGroupHints = groupHints;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mGroupHints);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<FieldClassification> CREATOR
            = new Parcelable.Creator<FieldClassification>() {
        @Override
        public FieldClassification[] newArray(int size) {
            return new FieldClassification[size];
        }

        @Override
        public FieldClassification createFromParcel(@NonNull Parcel in) {
            return new FieldClassification(in);
        }
    };

    @DataClass.Generated(
            time = 1675320464097L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/service/assist/classification/FieldClassification.java",
            inputSignatures = "private final @android.annotation.NonNull android.view.autofill.AutofillId mAutofillId\nprivate final @android.annotation.NonNull java.util.Set<java.lang.String> mHints\nprivate final @android.annotation.NonNull java.util.Set<java.lang.String> mGroupHints\npublic @android.annotation.NonNull android.view.autofill.AutofillId getAutofillId()\npublic @android.annotation.NonNull java.util.Set<java.lang.String> getHints()\npublic @android.annotation.SystemApi @android.annotation.NonNull java.util.Set<java.lang.String> getGroupHints()\nstatic  java.util.Set<java.lang.String> unparcelHints(android.os.Parcel)\n  void parcelHints(android.os.Parcel,int)\nstatic  java.util.Set<java.lang.String> unparcelGroupHints(android.os.Parcel)\n  void parcelGroupHints(android.os.Parcel,int)\nclass FieldClassification extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genConstructor=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
