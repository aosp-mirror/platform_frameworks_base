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

package android.view.translation;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.autofill.AutofillId;

import com.android.internal.util.DataClass;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wrapper class representing a translation request associated with a {@link android.view.View} to
 * be used by {@link android.service.translation.TranslationService}.
 */
@DataClass(genBuilder = false, genToString = true, genEqualsHashCode = true, genGetters = false,
        genHiddenConstructor = true)
public final class ViewTranslationRequest implements Parcelable {

    /**
     * Constant id for the default view text to be translated. This is used by
     * {@link Builder#setValue(String, TranslationRequestValue)}.
     */
    public static final String ID_TEXT = "text";

    /**
     * The {@link AutofillId} of the view associated with this request.
     */
    @NonNull
    private final AutofillId mAutofillId;

    @NonNull
    @DataClass.PluralOf("translationRequestValue")
    private final Map<String, TranslationRequestValue> mTranslationRequestValues;

    /**
     * Gets the corresponding {@link TranslationRequestValue} of the provided key.
     * @param key String id of the translation request value to be translated.
     * @return the {@link TranslationRequestValue}.
     * @throws IllegalArgumentException if the key does not exist.
     */
    @NonNull
    public TranslationRequestValue getValue(@NonNull String key) {
        Objects.requireNonNull(key, "key should not be null");
        if (!mTranslationRequestValues.containsKey(key)) {
            throw new IllegalArgumentException("Request does not contain value for key=" + key);
        }
        return mTranslationRequestValues.get(key);
    }

    /**
     * Returns all keys in this request as a {@link Set} of Strings. The keys are used by
     * {@link #getValue(String)} to get the {@link TranslationRequestValue}s.
     */
    @NonNull
    public Set<String> getKeys() {
        return mTranslationRequestValues.keySet();
    }


    /**
     * Returns the associated {@link AutofillId} of this request.
     */
    @NonNull
    public AutofillId getAutofillId() {
        return mAutofillId;
    }

    private static Map<String, TranslationRequestValue> defaultTranslationRequestValues() {
        return Collections.emptyMap();
    }

    /**
     * A builder for building ViewTranslationRequest.
     */
    public static final class Builder {

        private @NonNull AutofillId mAutofillId;
        private @NonNull Map<String, TranslationRequestValue> mTranslationRequestValues;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param autofillId The {@link AutofillId} of the view associated with this request.
         */
        public Builder(@NonNull AutofillId autofillId) {
            mAutofillId = autofillId;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAutofillId);
        }

        /**
         * Creates a new Builder.
         *
         * @param autofillId the {@link AutofillId} of the non-virtual view hosting the virtual view
         * hierarchy associated with this request.
        * @param virtualChildId the id of the virtual child, relative to the parent.
         */
        public Builder(@NonNull AutofillId autofillId, long virtualChildId) {
            mAutofillId = new AutofillId(autofillId, virtualChildId, AutofillId.NO_SESSION);
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAutofillId);
        }

        /**
         * Sets the corresponding {@link TranslationRequestValue} for the provided key.
         *
         * @param key The key for this translation request value.
         * @param value the translation request value holding the content to be translated.
         * @return this builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setValue(@NonNull String key, @NonNull TranslationRequestValue value) {
            if (mTranslationRequestValues == null) {
                setTranslationRequestValues(new ArrayMap<>());
            }
            mTranslationRequestValues.put(key, value);
            return this;
        }

        /**
         * Builds the instance. This builder should not be touched after calling this!
         */
        @NonNull
        public ViewTranslationRequest build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mTranslationRequestValues = defaultTranslationRequestValues();
            }
            ViewTranslationRequest o = new ViewTranslationRequest(
                    mAutofillId,
                    mTranslationRequestValues);
            return o;
        }

        Builder setTranslationRequestValues(@NonNull Map<String, TranslationRequestValue> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mTranslationRequestValues = value;
            return this;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/translation/ViewTranslationRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new ViewTranslationRequest.
     *
     * @param autofillId
     *   The {@link AutofillId} of the view associated with this request.
     * @hide
     */
    @DataClass.Generated.Member
    public ViewTranslationRequest(
            @NonNull AutofillId autofillId,
            @NonNull Map<String,TranslationRequestValue> translationRequestValues) {
        this.mAutofillId = autofillId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAutofillId);
        this.mTranslationRequestValues = translationRequestValues;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTranslationRequestValues);

        // onConstructed(); // You can define this method to get a callback
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "ViewTranslationRequest { " +
                "autofillId = " + mAutofillId + ", " +
                "translationRequestValues = " + mTranslationRequestValues +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(ViewTranslationRequest other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        ViewTranslationRequest that = (ViewTranslationRequest) o;
        //noinspection PointlessBooleanExpression
        return true
                && Objects.equals(mAutofillId, that.mAutofillId)
                && Objects.equals(mTranslationRequestValues, that.mTranslationRequestValues);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mAutofillId);
        _hash = 31 * _hash + Objects.hashCode(mTranslationRequestValues);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mAutofillId, flags);
        dest.writeMap(mTranslationRequestValues);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ ViewTranslationRequest(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        AutofillId autofillId = (AutofillId) in.readTypedObject(AutofillId.CREATOR);
        Map<String,TranslationRequestValue> translationRequestValues = new java.util.LinkedHashMap<>();
        in.readMap(translationRequestValues, TranslationRequestValue.class.getClassLoader());

        this.mAutofillId = autofillId;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAutofillId);
        this.mTranslationRequestValues = translationRequestValues;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTranslationRequestValues);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<ViewTranslationRequest> CREATOR
            = new Parcelable.Creator<ViewTranslationRequest>() {
        @Override
        public ViewTranslationRequest[] newArray(int size) {
            return new ViewTranslationRequest[size];
        }

        @Override
        public ViewTranslationRequest createFromParcel(@NonNull Parcel in) {
            return new ViewTranslationRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1617119791798L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/view/translation/ViewTranslationRequest.java",
            inputSignatures = "public static final  java.lang.String ID_TEXT\nprivate final @android.annotation.NonNull android.view.autofill.AutofillId mAutofillId\nprivate final @android.annotation.NonNull @com.android.internal.util.DataClass.PluralOf(\"translationRequestValue\") java.util.Map<java.lang.String,android.view.translation.TranslationRequestValue> mTranslationRequestValues\npublic @android.annotation.NonNull android.view.translation.TranslationRequestValue getValue(java.lang.String)\npublic @android.annotation.NonNull java.util.Set<java.lang.String> getKeys()\npublic @android.annotation.NonNull android.view.autofill.AutofillId getAutofillId()\nprivate static  java.util.Map<java.lang.String,android.view.translation.TranslationRequestValue> defaultTranslationRequestValues()\nclass ViewTranslationRequest extends java.lang.Object implements [android.os.Parcelable]\nprivate @android.annotation.NonNull android.view.autofill.AutofillId mAutofillId\nprivate @android.annotation.NonNull java.util.Map<java.lang.String,android.view.translation.TranslationRequestValue> mTranslationRequestValues\nprivate  long mBuilderFieldsSet\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.view.translation.ViewTranslationRequest.Builder setValue(java.lang.String,android.view.translation.TranslationRequestValue)\npublic @android.annotation.NonNull android.view.translation.ViewTranslationRequest build()\n  android.view.translation.ViewTranslationRequest.Builder setTranslationRequestValues(java.util.Map<java.lang.String,android.view.translation.TranslationRequestValue>)\nprivate  void checkNotUsed()\nclass Builder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genBuilder=false, genToString=true, genEqualsHashCode=true, genGetters=false, genHiddenConstructor=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
