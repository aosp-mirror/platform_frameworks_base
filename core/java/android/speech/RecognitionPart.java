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

package android.speech;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcelable;

import com.android.internal.util.DataClass;
import com.android.internal.util.Preconditions;

/**
 * Info about a single recognition part.
 *
 * <p> A recognition part represents a recognized word or character, as well as any potential
 * adjacent punctuation, that is returned by the {@link SpeechRecognizer}.
 *
 * <p> Each recognition part is described with a {@link String} denoting the raw text.
 * Additionally, if formatting is enabled with {@link RecognizerIntent#EXTRA_ENABLE_FORMATTING},
 * another {@link String} representation exists denoting the formatted text.
 *
 * <p> If the timestamps are requested with {@link RecognizerIntent#EXTRA_REQUEST_WORD_TIMING}, each
 * recognition part will contain a value representing the offset of the beginning of this part from
 * the start of the recognition session in milliseconds.
 *
 * <p> If the confidence levels are requested with
 * {@link RecognizerIntent#EXTRA_REQUEST_WORD_CONFIDENCE}, each recognition part will contain
 * a value describing the level of recognition confidence.
 */
@DataClass(
        genBuilder = true,
        genEqualsHashCode = true,
        genHiddenConstDefs = true,
        genToString = true)
public final class RecognitionPart implements Parcelable {

    /** Confidence level not requested. */
    public static final int CONFIDENCE_LEVEL_UNKNOWN = 0;

    /** Lowest level of confidence out of five levels. */
    public static final int CONFIDENCE_LEVEL_LOW = 1;

    /** Second-lowest level of confidence out of five levels. */
    public static final int CONFIDENCE_LEVEL_MEDIUM_LOW = 2;

    /** Medium level of confidence out of five levels. */
    public static final int CONFIDENCE_LEVEL_MEDIUM = 3;

    /** Second-highest level of confidence out of five levels. */
    public static final int CONFIDENCE_LEVEL_MEDIUM_HIGH = 4;

    /** Highest level of confidence out of five levels. */
    public static final int CONFIDENCE_LEVEL_HIGH = 5;

    /** The {@code non-null} raw text version of the recognized part of the result. */
    @NonNull
    private final String mRawText;

    /**
     * The formatted text version of the recognized part of the result. If formatting is enabled
     * with {@link RecognizerIntent#EXTRA_ENABLE_FORMATTING}, it has a {@code non-null} value.
     *
     * <p> Otherwise, it should be {@code null} by default.
     */
    @Nullable
    private final String mFormattedText;
    private static String defaultFormattedText() {
        return null;
    }

    /**
     * Non-negative offset of the beginning of this part from
     * the start of the recognition session in milliseconds
     * if requested with {@link RecognizerIntent#EXTRA_REQUEST_WORD_TIMING}.
     *
     * <p> Otherwise, this should equal 0.
     */
    private final long mTimestampMillis;
    private static long defaultTimestampMillis() {
        return 0;
    }

    /**
     * The level of confidence for this part if requested
     * with {@link RecognizerIntent#EXTRA_REQUEST_WORD_CONFIDENCE}.
     *
     * <p> Otherwise, this should equal {@link #CONFIDENCE_LEVEL_UNKNOWN}.
     */
    @ConfidenceLevel
    private final int mConfidenceLevel;
    @ConfidenceLevel
    private static int defaultConfidenceLevel() {
        return CONFIDENCE_LEVEL_UNKNOWN;
    }

    private void onConstructed() {
        Preconditions.checkArgumentNonnegative(mTimestampMillis,
                "The timestamp must be non-negative.");
    }

    @DataClass.Suppress("setFormattedText")
    abstract static class BaseBuilder {
        /**
         * The formatted text version of the recognized part of the result. If formatting is enabled
         * with {@link RecognizerIntent#EXTRA_ENABLE_FORMATTING}, it has a {@code non-null} value.
         *
         * <p> Otherwise, it should be {@code null} by default.
         */
        @NonNull
        public Builder setFormattedText(@NonNull String value) {
            // Method explicitly defined, so that the argument can be checked for non-null.
            com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, value);

            final Builder builder = (Builder) this;
            builder.checkNotUsed();
            builder.mBuilderFieldsSet |= 0x2;
            builder.mFormattedText = value;
            return builder;
        }
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/speech/RecognitionPart.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @android.annotation.IntDef(prefix = "CONFIDENCE_LEVEL_", value = {
        CONFIDENCE_LEVEL_UNKNOWN,
        CONFIDENCE_LEVEL_LOW,
        CONFIDENCE_LEVEL_MEDIUM_LOW,
        CONFIDENCE_LEVEL_MEDIUM,
        CONFIDENCE_LEVEL_MEDIUM_HIGH,
        CONFIDENCE_LEVEL_HIGH
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface ConfidenceLevel {}

    /** @hide */
    @DataClass.Generated.Member
    public static String confidenceLevelToString(@ConfidenceLevel int value) {
        switch (value) {
            case CONFIDENCE_LEVEL_UNKNOWN:
                    return "CONFIDENCE_LEVEL_UNKNOWN";
            case CONFIDENCE_LEVEL_LOW:
                    return "CONFIDENCE_LEVEL_LOW";
            case CONFIDENCE_LEVEL_MEDIUM_LOW:
                    return "CONFIDENCE_LEVEL_MEDIUM_LOW";
            case CONFIDENCE_LEVEL_MEDIUM:
                    return "CONFIDENCE_LEVEL_MEDIUM";
            case CONFIDENCE_LEVEL_MEDIUM_HIGH:
                    return "CONFIDENCE_LEVEL_MEDIUM_HIGH";
            case CONFIDENCE_LEVEL_HIGH:
                    return "CONFIDENCE_LEVEL_HIGH";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ RecognitionPart(
            @NonNull String rawText,
            @Nullable String formattedText,
            long timestampMillis,
            @ConfidenceLevel int confidenceLevel) {
        this.mRawText = rawText;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mRawText);
        this.mFormattedText = formattedText;
        this.mTimestampMillis = timestampMillis;
        this.mConfidenceLevel = confidenceLevel;

        if (!(mConfidenceLevel == CONFIDENCE_LEVEL_UNKNOWN)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_LOW)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM_LOW)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM_HIGH)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_HIGH)) {
            throw new java.lang.IllegalArgumentException(
                    "confidenceLevel was " + mConfidenceLevel + " but must be one of: "
                            + "CONFIDENCE_LEVEL_UNKNOWN(" + CONFIDENCE_LEVEL_UNKNOWN + "), "
                            + "CONFIDENCE_LEVEL_LOW(" + CONFIDENCE_LEVEL_LOW + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM_LOW(" + CONFIDENCE_LEVEL_MEDIUM_LOW + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM(" + CONFIDENCE_LEVEL_MEDIUM + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM_HIGH(" + CONFIDENCE_LEVEL_MEDIUM_HIGH + "), "
                            + "CONFIDENCE_LEVEL_HIGH(" + CONFIDENCE_LEVEL_HIGH + ")");
        }


        onConstructed();
    }

    /**
     * The {@code non-null} raw text version of the recognized part of the result.
     */
    @DataClass.Generated.Member
    public @NonNull String getRawText() {
        return mRawText;
    }

    /**
     * The formatted text version of the recognized part of the result. If formatting is enabled
     * with {@link RecognizerIntent#EXTRA_ENABLE_FORMATTING}, it has a {@code non-null} value.
     *
     * <p> Otherwise, it should be {@code null} by default.
     */
    @DataClass.Generated.Member
    public @Nullable String getFormattedText() {
        return mFormattedText;
    }

    /**
     * Non-negative offset of the beginning of this part from
     * the start of the recognition session in milliseconds
     * if requested with {@link RecognizerIntent#EXTRA_REQUEST_WORD_TIMING}.
     *
     * <p> Otherwise, this should equal 0.
     */
    @DataClass.Generated.Member
    public long getTimestampMillis() {
        return mTimestampMillis;
    }

    /**
     * The level of confidence for this part if requested
     * with {@link RecognizerIntent#EXTRA_REQUEST_WORD_CONFIDENCE}.
     *
     * <p> Otherwise, this should equal {@link #CONFIDENCE_LEVEL_UNKNOWN}.
     */
    @DataClass.Generated.Member
    public @ConfidenceLevel int getConfidenceLevel() {
        return mConfidenceLevel;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "RecognitionPart { " +
                "rawText = " + mRawText + ", " +
                "formattedText = " + mFormattedText + ", " +
                "timestampMillis = " + mTimestampMillis + ", " +
                "confidenceLevel = " + confidenceLevelToString(mConfidenceLevel) +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(RecognitionPart other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        RecognitionPart that = (RecognitionPart) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Objects.equals(mRawText, that.mRawText)
                && java.util.Objects.equals(mFormattedText, that.mFormattedText)
                && mTimestampMillis == that.mTimestampMillis
                && mConfidenceLevel == that.mConfidenceLevel;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Objects.hashCode(mRawText);
        _hash = 31 * _hash + java.util.Objects.hashCode(mFormattedText);
        _hash = 31 * _hash + Long.hashCode(mTimestampMillis);
        _hash = 31 * _hash + mConfidenceLevel;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mFormattedText != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeString(mRawText);
        if (mFormattedText != null) dest.writeString(mFormattedText);
        dest.writeLong(mTimestampMillis);
        dest.writeInt(mConfidenceLevel);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ RecognitionPart(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        String rawText = in.readString();
        String formattedText = (flg & 0x2) == 0 ? null : in.readString();
        long timestampMillis = in.readLong();
        int confidenceLevel = in.readInt();

        this.mRawText = rawText;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mRawText);
        this.mFormattedText = formattedText;
        this.mTimestampMillis = timestampMillis;
        this.mConfidenceLevel = confidenceLevel;

        if (!(mConfidenceLevel == CONFIDENCE_LEVEL_UNKNOWN)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_LOW)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM_LOW)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_MEDIUM_HIGH)
                && !(mConfidenceLevel == CONFIDENCE_LEVEL_HIGH)) {
            throw new java.lang.IllegalArgumentException(
                    "confidenceLevel was " + mConfidenceLevel + " but must be one of: "
                            + "CONFIDENCE_LEVEL_UNKNOWN(" + CONFIDENCE_LEVEL_UNKNOWN + "), "
                            + "CONFIDENCE_LEVEL_LOW(" + CONFIDENCE_LEVEL_LOW + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM_LOW(" + CONFIDENCE_LEVEL_MEDIUM_LOW + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM(" + CONFIDENCE_LEVEL_MEDIUM + "), "
                            + "CONFIDENCE_LEVEL_MEDIUM_HIGH(" + CONFIDENCE_LEVEL_MEDIUM_HIGH + "), "
                            + "CONFIDENCE_LEVEL_HIGH(" + CONFIDENCE_LEVEL_HIGH + ")");
        }


        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<RecognitionPart> CREATOR
            = new Parcelable.Creator<RecognitionPart>() {
        @Override
        public RecognitionPart[] newArray(int size) {
            return new RecognitionPart[size];
        }

        @Override
        public RecognitionPart createFromParcel(@NonNull android.os.Parcel in) {
            return new RecognitionPart(in);
        }
    };

    /**
     * A builder for {@link RecognitionPart}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder extends BaseBuilder {

        private @NonNull String mRawText;
        private @Nullable String mFormattedText;
        private long mTimestampMillis;
        private @ConfidenceLevel int mConfidenceLevel;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param rawText
         *   The {@code non-null} raw text version of the recognized part of the result.
         */
        public Builder(
                @NonNull String rawText) {
            mRawText = rawText;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mRawText);
        }

        /**
         * The {@code non-null} raw text version of the recognized part of the result.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setRawText(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mRawText = value;
            return this;
        }

        /**
         * Non-negative offset of the beginning of this part from
         * the start of the recognition session in milliseconds
         * if requested with {@link RecognizerIntent#EXTRA_REQUEST_WORD_TIMING}.
         *
         * <p> Otherwise, this should equal 0.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTimestampMillis(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mTimestampMillis = value;
            return this;
        }

        /**
         * The level of confidence for this part if requested
         * with {@link RecognizerIntent#EXTRA_REQUEST_WORD_CONFIDENCE}.
         *
         * <p> Otherwise, this should equal {@link #CONFIDENCE_LEVEL_UNKNOWN}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setConfidenceLevel(@ConfidenceLevel int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mConfidenceLevel = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull RecognitionPart build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mFormattedText = defaultFormattedText();
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mTimestampMillis = defaultTimestampMillis();
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mConfidenceLevel = defaultConfidenceLevel();
            }
            RecognitionPart o = new RecognitionPart(
                    mRawText,
                    mFormattedText,
                    mTimestampMillis,
                    mConfidenceLevel);
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
            time = 1677008539189L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/speech/RecognitionPart.java",
            inputSignatures = "public static final  int CONFIDENCE_LEVEL_UNKNOWN\npublic static final  int CONFIDENCE_LEVEL_LOW\npublic static final  int CONFIDENCE_LEVEL_MEDIUM_LOW\npublic static final  int CONFIDENCE_LEVEL_MEDIUM\npublic static final  int CONFIDENCE_LEVEL_MEDIUM_HIGH\npublic static final  int CONFIDENCE_LEVEL_HIGH\nprivate final @android.annotation.NonNull java.lang.String mRawText\nprivate final @android.annotation.Nullable java.lang.String mFormattedText\nprivate final  long mTimestampMillis\nprivate final @android.speech.RecognitionPart.ConfidenceLevel int mConfidenceLevel\nprivate static  java.lang.String defaultFormattedText()\nprivate static  long defaultTimestampMillis()\nprivate static @android.speech.RecognitionPart.ConfidenceLevel int defaultConfidenceLevel()\nprivate  void onConstructed()\nclass RecognitionPart extends java.lang.Object implements [android.os.Parcelable]\npublic @android.annotation.NonNull android.speech.RecognitionPart.Builder setFormattedText(java.lang.String)\nclass BaseBuilder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genBuilder=true, genEqualsHashCode=true, genHiddenConstDefs=true, genToString=true)\npublic @android.annotation.NonNull android.speech.RecognitionPart.Builder setFormattedText(java.lang.String)\nclass BaseBuilder extends java.lang.Object implements []")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
