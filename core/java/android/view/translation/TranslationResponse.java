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

package android.view.translation;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import com.android.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Response from the translation service, which contains the translated result.
 */
@DataClass(genBuilder = true, genToString = true, genHiddenConstDefs = true)
public final class TranslationResponse implements Parcelable {

    /**
     * The translation service was successful in translating.
     */
    public static final int TRANSLATION_STATUS_SUCCESS = 0;
    /**
     * The translation service returned unknown translation result.
     */
    public static final int TRANSLATION_STATUS_UNKNOWN_ERROR = 1;
    /**
     * The languages of the request is not available to be translated.
     */
    public static final int TRANSLATION_STATUS_CONTEXT_UNSUPPORTED = 2;

    /**
     * The translation result status code.
     */
    private final @TranslationStatus int mTranslationStatus;

    /**
     * List of translated {@link TranslationResponseValue}s. The key of entries in this list
     * will be their respective index in {@link TranslationRequest#getTranslationRequestValues()}.
     */
    @NonNull
    private final SparseArray<TranslationResponseValue> mTranslationResponseValues;

    /**
     * List of translated {@link ViewTranslationResponse}s. The key of entries in this list
     * will be their respective index in {@link TranslationRequest#getViewTranslationRequests()}.
     */
    @NonNull
    private final SparseArray<ViewTranslationResponse> mViewTranslationResponses;

    /**
     * Whether this response contains complete translated values, or is the final response in a
     * series of partial responses.
     *
     * <p>This is {@code true} by default.</p>
     */
    private final boolean mFinalResponse;

    abstract static class BaseBuilder {

        /**
         * @removed Use {@link Builder#Builder(int)}.
         * @hide
         */
        @Deprecated
        public abstract Builder setTranslationStatus(@TranslationStatus int value);

        /**
         * Adds {@link TranslationResponseValue} to be translated. The input
         * TranslationResponseValue format should match those provided by the
         * {@link android.view.translation.Translator}'s targetSpec.
         *
         * @param value the translated value.
         * @return this Builder.
         */
        @NonNull
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder setTranslationResponseValue(int index,
                @NonNull TranslationResponseValue value) {
            Objects.requireNonNull(value, "value should not be null");
            final Builder builder = (Builder) this;

            if (builder.mTranslationResponseValues == null) {
                builder.setTranslationResponseValues(new SparseArray<>());
            }
            builder.mTranslationResponseValues.put(index, value);
            return builder;
        }

        /**
         * Sets the list of {@link ViewTranslationResponse} to be translated. The input
         * ViewTranslationResponse contains {@link TranslationResponseValue}s whose  format should
         * match those provided by the {@link android.view.translation.Translator}'s targetSpec.
         *
         * @param response the translated response.
         * @return this Builder.
         */
        @NonNull
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder setViewTranslationResponse(int index,
                @NonNull ViewTranslationResponse response) {
            Objects.requireNonNull(response, "value should not be null");
            final Builder builder = (Builder) this;

            if (builder.mViewTranslationResponses == null) {
                builder.setViewTranslationResponses(new SparseArray<>());
            }
            builder.mViewTranslationResponses.put(index, response);
            return builder;
        }
    }

    private static SparseArray<TranslationResponseValue> defaultTranslationResponseValues() {
        return new SparseArray<>();
    }

    private static SparseArray<ViewTranslationResponse> defaultViewTranslationResponses() {
        return new SparseArray<>();
    }

    private static boolean defaultFinalResponse() {
        return true;
    }




    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/translation/TranslationResponse.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(prefix = "TRANSLATION_STATUS_", value = {
        TRANSLATION_STATUS_SUCCESS,
        TRANSLATION_STATUS_UNKNOWN_ERROR,
        TRANSLATION_STATUS_CONTEXT_UNSUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface TranslationStatus {}

    /** @hide */
    @DataClass.Generated.Member
    public static String translationStatusToString(@TranslationStatus int value) {
        switch (value) {
            case TRANSLATION_STATUS_SUCCESS:
                    return "TRANSLATION_STATUS_SUCCESS";
            case TRANSLATION_STATUS_UNKNOWN_ERROR:
                    return "TRANSLATION_STATUS_UNKNOWN_ERROR";
            case TRANSLATION_STATUS_CONTEXT_UNSUPPORTED:
                    return "TRANSLATION_STATUS_CONTEXT_UNSUPPORTED";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ TranslationResponse(
            @TranslationStatus int translationStatus,
            @NonNull SparseArray<TranslationResponseValue> translationResponseValues,
            @NonNull SparseArray<ViewTranslationResponse> viewTranslationResponses,
            boolean finalResponse) {
        this.mTranslationStatus = translationStatus;

        if (!(mTranslationStatus == TRANSLATION_STATUS_SUCCESS)
                && !(mTranslationStatus == TRANSLATION_STATUS_UNKNOWN_ERROR)
                && !(mTranslationStatus == TRANSLATION_STATUS_CONTEXT_UNSUPPORTED)) {
            throw new java.lang.IllegalArgumentException(
                    "translationStatus was " + mTranslationStatus + " but must be one of: "
                            + "TRANSLATION_STATUS_SUCCESS(" + TRANSLATION_STATUS_SUCCESS + "), "
                            + "TRANSLATION_STATUS_UNKNOWN_ERROR(" + TRANSLATION_STATUS_UNKNOWN_ERROR + "), "
                            + "TRANSLATION_STATUS_CONTEXT_UNSUPPORTED(" + TRANSLATION_STATUS_CONTEXT_UNSUPPORTED + ")");
        }

        this.mTranslationResponseValues = translationResponseValues;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTranslationResponseValues);
        this.mViewTranslationResponses = viewTranslationResponses;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mViewTranslationResponses);
        this.mFinalResponse = finalResponse;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The translation result status code.
     */
    @DataClass.Generated.Member
    public @TranslationStatus int getTranslationStatus() {
        return mTranslationStatus;
    }

    /**
     * List of translated {@link TranslationResponseValue}s. The key of entries in this list
     * will be their respective index in {@link TranslationRequest#getTranslationRequestValues()}.
     */
    @DataClass.Generated.Member
    public @NonNull SparseArray<TranslationResponseValue> getTranslationResponseValues() {
        return mTranslationResponseValues;
    }

    /**
     * List of translated {@link ViewTranslationResponse}s. The key of entries in this list
     * will be their respective index in {@link TranslationRequest#getViewTranslationRequests()}.
     */
    @DataClass.Generated.Member
    public @NonNull SparseArray<ViewTranslationResponse> getViewTranslationResponses() {
        return mViewTranslationResponses;
    }

    /**
     * Whether this response contains complete translated values, or is the final response in a
     * series of partial responses.
     *
     * <p>This is {@code true} by default.</p>
     */
    @DataClass.Generated.Member
    public boolean isFinalResponse() {
        return mFinalResponse;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "TranslationResponse { " +
                "translationStatus = " + translationStatusToString(mTranslationStatus) + ", " +
                "translationResponseValues = " + mTranslationResponseValues + ", " +
                "viewTranslationResponses = " + mViewTranslationResponses + ", " +
                "finalResponse = " + mFinalResponse +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mFinalResponse) flg |= 0x8;
        dest.writeByte(flg);
        dest.writeInt(mTranslationStatus);
        dest.writeSparseArray(mTranslationResponseValues);
        dest.writeSparseArray(mViewTranslationResponses);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ TranslationResponse(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean finalResponse = (flg & 0x8) != 0;
        int translationStatus = in.readInt();
        SparseArray<TranslationResponseValue> translationResponseValues = (SparseArray) in.readSparseArray(TranslationResponseValue.class.getClassLoader());
        SparseArray<ViewTranslationResponse> viewTranslationResponses = (SparseArray) in.readSparseArray(ViewTranslationResponse.class.getClassLoader());

        this.mTranslationStatus = translationStatus;

        if (!(mTranslationStatus == TRANSLATION_STATUS_SUCCESS)
                && !(mTranslationStatus == TRANSLATION_STATUS_UNKNOWN_ERROR)
                && !(mTranslationStatus == TRANSLATION_STATUS_CONTEXT_UNSUPPORTED)) {
            throw new java.lang.IllegalArgumentException(
                    "translationStatus was " + mTranslationStatus + " but must be one of: "
                            + "TRANSLATION_STATUS_SUCCESS(" + TRANSLATION_STATUS_SUCCESS + "), "
                            + "TRANSLATION_STATUS_UNKNOWN_ERROR(" + TRANSLATION_STATUS_UNKNOWN_ERROR + "), "
                            + "TRANSLATION_STATUS_CONTEXT_UNSUPPORTED(" + TRANSLATION_STATUS_CONTEXT_UNSUPPORTED + ")");
        }

        this.mTranslationResponseValues = translationResponseValues;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTranslationResponseValues);
        this.mViewTranslationResponses = viewTranslationResponses;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mViewTranslationResponses);
        this.mFinalResponse = finalResponse;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<TranslationResponse> CREATOR
            = new Parcelable.Creator<TranslationResponse>() {
        @Override
        public TranslationResponse[] newArray(int size) {
            return new TranslationResponse[size];
        }

        @Override
        public TranslationResponse createFromParcel(@NonNull Parcel in) {
            return new TranslationResponse(in);
        }
    };

    /**
     * A builder for {@link TranslationResponse}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder extends BaseBuilder {

        private @TranslationStatus int mTranslationStatus;
        private @NonNull SparseArray<TranslationResponseValue> mTranslationResponseValues;
        private @NonNull SparseArray<ViewTranslationResponse> mViewTranslationResponses;
        private boolean mFinalResponse;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param translationStatus
         *   The translation result status code.
         */
        public Builder(
                @TranslationStatus int translationStatus) {
            mTranslationStatus = translationStatus;

            if (!(mTranslationStatus == TRANSLATION_STATUS_SUCCESS)
                    && !(mTranslationStatus == TRANSLATION_STATUS_UNKNOWN_ERROR)
                    && !(mTranslationStatus == TRANSLATION_STATUS_CONTEXT_UNSUPPORTED)) {
                throw new java.lang.IllegalArgumentException(
                        "translationStatus was " + mTranslationStatus + " but must be one of: "
                                + "TRANSLATION_STATUS_SUCCESS(" + TRANSLATION_STATUS_SUCCESS + "), "
                                + "TRANSLATION_STATUS_UNKNOWN_ERROR(" + TRANSLATION_STATUS_UNKNOWN_ERROR + "), "
                                + "TRANSLATION_STATUS_CONTEXT_UNSUPPORTED(" + TRANSLATION_STATUS_CONTEXT_UNSUPPORTED + ")");
            }

        }

        /**
         * The translation result status code.
         * @removed
         */
        @DataClass.Generated.Member
        @Override
        @Deprecated
        public @NonNull Builder setTranslationStatus(@TranslationStatus int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mTranslationStatus = value;
            return this;
        }

        /**
         * List of translated {@link TranslationResponseValue}s. The key of entries in this list
         * will be their respective index in {@link TranslationRequest#getTranslationRequestValues()}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTranslationResponseValues(@NonNull SparseArray<TranslationResponseValue> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mTranslationResponseValues = value;
            return this;
        }

        /**
         * List of translated {@link ViewTranslationResponse}s. The key of entries in this list
         * will be their respective index in {@link TranslationRequest#getViewTranslationRequests()}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setViewTranslationResponses(@NonNull SparseArray<ViewTranslationResponse> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mViewTranslationResponses = value;
            return this;
        }

        /**
         * Whether this response contains complete translated values, or is the final response in a
         * series of partial responses.
         *
         * <p>This is {@code true} by default.</p>
         */
        @DataClass.Generated.Member
        public @NonNull Builder setFinalResponse(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mFinalResponse = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull TranslationResponse build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mTranslationResponseValues = defaultTranslationResponseValues();
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mViewTranslationResponses = defaultViewTranslationResponses();
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mFinalResponse = defaultFinalResponse();
            }
            TranslationResponse o = new TranslationResponse(
                    mTranslationStatus,
                    mTranslationResponseValues,
                    mViewTranslationResponses,
                    mFinalResponse);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x10) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1621972659130L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/view/translation/TranslationResponse.java",
            inputSignatures = "public static final  int TRANSLATION_STATUS_SUCCESS\npublic static final  int TRANSLATION_STATUS_UNKNOWN_ERROR\npublic static final  int TRANSLATION_STATUS_CONTEXT_UNSUPPORTED\nprivate final @android.view.translation.TranslationResponse.TranslationStatus int mTranslationStatus\nprivate final @android.annotation.NonNull android.util.SparseArray<android.view.translation.TranslationResponseValue> mTranslationResponseValues\nprivate final @android.annotation.NonNull android.util.SparseArray<android.view.translation.ViewTranslationResponse> mViewTranslationResponses\nprivate final  boolean mFinalResponse\nprivate static  android.util.SparseArray<android.view.translation.TranslationResponseValue> defaultTranslationResponseValues()\nprivate static  android.util.SparseArray<android.view.translation.ViewTranslationResponse> defaultViewTranslationResponses()\nprivate static  boolean defaultFinalResponse()\nclass TranslationResponse extends java.lang.Object implements [android.os.Parcelable]\npublic abstract @java.lang.Deprecated android.view.translation.TranslationResponse.Builder setTranslationStatus(int)\npublic @android.annotation.NonNull @java.lang.SuppressWarnings android.view.translation.TranslationResponse.Builder setTranslationResponseValue(int,android.view.translation.TranslationResponseValue)\npublic @android.annotation.NonNull @java.lang.SuppressWarnings android.view.translation.TranslationResponse.Builder setViewTranslationResponse(int,android.view.translation.ViewTranslationResponse)\nclass BaseBuilder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genBuilder=true, genToString=true, genHiddenConstDefs=true)\npublic abstract @java.lang.Deprecated android.view.translation.TranslationResponse.Builder setTranslationStatus(int)\npublic @android.annotation.NonNull @java.lang.SuppressWarnings android.view.translation.TranslationResponse.Builder setTranslationResponseValue(int,android.view.translation.TranslationResponseValue)\npublic @android.annotation.NonNull @java.lang.SuppressWarnings android.view.translation.TranslationResponse.Builder setViewTranslationResponse(int,android.view.translation.ViewTranslationResponse)\nclass BaseBuilder extends java.lang.Object implements []")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
