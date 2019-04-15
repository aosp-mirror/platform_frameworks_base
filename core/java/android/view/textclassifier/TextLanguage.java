/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.Locale;
import java.util.Map;

/**
 * Represents the result of language detection of a piece of text.
 * <p>
 * This contains a list of locales, each paired with a confidence score, sorted in decreasing
 * order of those scores. E.g., for a given input text, the model may return
 * {@code [<"en", 0.85>, <"fr", 0.15>]}. This sample result means the model reports that it is
 * 85% likely that the entire text is in English and 15% likely that the entire text is in French,
 * etc. It does not mean that 85% of the input is in English and 15% is in French.
 */
public final class TextLanguage implements Parcelable {

    public static final @android.annotation.NonNull Creator<TextLanguage> CREATOR = new Creator<TextLanguage>() {
        @Override
        public TextLanguage createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public TextLanguage[] newArray(int size) {
            return new TextLanguage[size];
        }
    };

    static final TextLanguage EMPTY = new Builder().build();

    @Nullable private final String mId;
    private final EntityConfidence mEntityConfidence;
    private final Bundle mBundle;

    private TextLanguage(
            @Nullable String id,
            EntityConfidence entityConfidence,
            Bundle bundle) {
        mId = id;
        mEntityConfidence = entityConfidence;
        mBundle = bundle;
    }

    /**
     * Returns the id, if one exists, for this object.
     */
    @Nullable
    public String getId() {
        return mId;
    }

    /**
     * Returns the number of possible locales for the processed text.
     */
    @IntRange(from = 0)
    public int getLocaleHypothesisCount() {
        return mEntityConfidence.getEntities().size();
    }

    /**
     * Returns the language locale at the specified index. Locales are ordered from high
     * confidence to low confidence.
     * <p>
     * See {@link #getLocaleHypothesisCount()} for the number of locales available.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     */
    @NonNull
    public ULocale getLocale(int index) {
        return ULocale.forLanguageTag(mEntityConfidence.getEntities().get(index));
    }

    /**
     * Returns the confidence score for the specified language locale. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the locale was not found for
     * the processed text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(@NonNull ULocale locale) {
        return mEntityConfidence.getConfidenceScore(locale.toLanguageTag());
    }

    /**
     * Returns a bundle containing non-structured extra information about this result. What is
     * returned in the extras is specific to the {@link TextClassifier} implementation.
     *
     * <p><b>NOTE: </b>Do not modify this bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mBundle;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.US,
                "TextLanguage {id=%s, locales=%s, bundle=%s}",
                mId, mEntityConfidence, mBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        mEntityConfidence.writeToParcel(dest, flags);
        dest.writeBundle(mBundle);
    }

    private static TextLanguage readFromParcel(Parcel in) {
        return new TextLanguage(
                in.readString(),
                EntityConfidence.CREATOR.createFromParcel(in),
                in.readBundle());
    }

    /**
     * Builder used to build TextLanguage objects.
     */
    public static final class Builder {

        @Nullable private String mId;
        private final Map<String, Float> mEntityConfidenceMap = new ArrayMap<>();
        @Nullable private Bundle mBundle;

        /**
         * Sets a language locale for the processed text and assigns a confidence score. If the
         * locale has already been set, this updates it.
         *
         * @param confidenceScore a value from 0 (low confidence) to 1 (high confidence).
         *      0 implies the locale does not exist for the processed text.
         *      Values greater than 1 are clamped to 1.
         */
        @NonNull
        public Builder putLocale(
                @NonNull ULocale locale,
                @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            Preconditions.checkNotNull(locale);
            mEntityConfidenceMap.put(locale.toLanguageTag(), confidenceScore);
            return this;
        }

        /**
         * Sets an optional id for the TextLanguage object.
         */
        @NonNull
        public Builder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        /**
         * Sets a bundle containing non-structured extra information about the TextLanguage object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle bundle) {
            mBundle = Preconditions.checkNotNull(bundle);
            return this;
        }

        /**
         * Builds and returns a new TextLanguage object.
         * <p>
         * If necessary, this method will verify fields, clamp them, and make them immutable.
         */
        @NonNull
        public TextLanguage build() {
            mBundle = mBundle == null ? Bundle.EMPTY : mBundle;
            return new TextLanguage(
                    mId,
                    new EntityConfidence(mEntityConfidenceMap),
                    mBundle);
        }
    }

    /**
     * A request object for detecting the language of a piece of text.
     */
    public static final class Request implements Parcelable {

        public static final @android.annotation.NonNull Creator<Request> CREATOR = new Creator<Request>() {
            @Override
            public Request createFromParcel(Parcel in) {
                return readFromParcel(in);
            }

            @Override
            public Request[] newArray(int size) {
                return new Request[size];
            }
        };

        private final CharSequence mText;
        private final Bundle mExtra;
        @Nullable private String mCallingPackageName;

        private Request(CharSequence text, Bundle bundle) {
            mText = text;
            mExtra = bundle;
        }

        /**
         * Returns the text to process.
         */
        @NonNull
        public CharSequence getText() {
            return mText;
        }

        /**
         * Sets the name of the package that is sending this request.
         * Package-private for SystemTextClassifier's use.
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void setCallingPackageName(@Nullable String callingPackageName) {
            mCallingPackageName = callingPackageName;
        }

        /**
         * Returns the name of the package that sent this request.
         * This returns null if no calling package name is set.
         */
        @Nullable
        public String getCallingPackageName() {
            return mCallingPackageName;
        }

        /**
         * Returns a bundle containing non-structured extra information about this request.
         *
         * <p><b>NOTE: </b>Do not modify this bundle.
         */
        @NonNull
        public Bundle getExtras() {
            return mExtra;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeCharSequence(mText);
            dest.writeString(mCallingPackageName);
            dest.writeBundle(mExtra);
        }

        private static Request readFromParcel(Parcel in) {
            final CharSequence text = in.readCharSequence();
            final String callingPackageName = in.readString();
            final Bundle extra = in.readBundle();

            final Request request = new Request(text, extra);
            request.setCallingPackageName(callingPackageName);
            return request;
        }

        /**
         * A builder for building TextLanguage requests.
         */
        public static final class Builder {

            private final CharSequence mText;
            @Nullable private Bundle mBundle;

            /**
             * Creates a builder to build TextLanguage requests.
             *
             * @param text the text to process.
             */
            public Builder(@NonNull CharSequence text) {
                mText = Preconditions.checkNotNull(text);
            }

            /**
             * Sets a bundle containing non-structured extra information about the request.
             */
            @NonNull
            public Builder setExtras(@NonNull Bundle bundle) {
                mBundle = Preconditions.checkNotNull(bundle);
                return this;
            }

            /**
             * Builds and returns a new TextLanguage request object.
             * <p>
             * If necessary, this method will verify fields, clamp them, and make them immutable.
             */
            @NonNull
            public Request build() {
                return new Request(mText.toString(), mBundle == null ? Bundle.EMPTY : mBundle);
            }
        }
    }
}
