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

package android.view.textclassifier;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannedString;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassifier.EntityType;
import android.view.textclassifier.TextClassifier.Utils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Information about where text selection should be.
 */
public final class TextSelection implements Parcelable {

    private final int mStartIndex;
    private final int mEndIndex;
    private final EntityConfidence mEntityConfidence;
    @Nullable private final String mId;
    @Nullable
    private final TextClassification mTextClassification;
    private final Bundle mExtras;

    private TextSelection(
            int startIndex, int endIndex, Map<String, Float> entityConfidence, String id,
            @Nullable TextClassification textClassification,
            Bundle extras) {
        mStartIndex = startIndex;
        mEndIndex = endIndex;
        mEntityConfidence = new EntityConfidence(entityConfidence);
        mId = id;
        mTextClassification = textClassification;
        mExtras = extras;
    }

    /**
     * Returns the start index of the text selection.
     */
    public int getSelectionStartIndex() {
        return mStartIndex;
    }

    /**
     * Returns the end index of the text selection.
     */
    public int getSelectionEndIndex() {
        return mEndIndex;
    }

    /**
     * Returns the number of entities found in the classified text.
     */
    @IntRange(from = 0)
    public int getEntityCount() {
        return mEntityConfidence.getEntities().size();
    }

    /**
     * Returns the entity at the specified index. Entities are ordered from high confidence
     * to low confidence.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getEntityCount() for the number of entities available.
     */
    @NonNull
    @EntityType
    public String getEntity(int index) {
        return mEntityConfidence.getEntities().get(index);
    }

    /**
     * Returns the confidence score for the specified entity. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the entity was not found for the
     * classified text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(@EntityType String entity) {
        return mEntityConfidence.getConfidenceScore(entity);
    }

    /**
     * Returns the id, if one exists, for this object.
     */
    @Nullable
    public String getId() {
        return mId;
    }

    /**
     * Returns the text classification result of the suggested selection span. Enables the text
     * classification by calling
     * {@link TextSelection.Request.Builder#setIncludeTextClassification(boolean)}. If the text
     * classifier does not support it, a {@code null} is returned.
     *
     * @see TextSelection.Request.Builder#setIncludeTextClassification(boolean)
     */
    @Nullable
    public TextClassification getTextClassification() {
        return mTextClassification;
    }

    /**
     * Returns the extended data.
     *
     * <p><b>NOTE: </b>Do not modify this bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /** @hide */
    public TextSelection.Builder toBuilder() {
        return new TextSelection.Builder(mStartIndex, mEndIndex)
                .setId(mId)
                .setEntityConfidence(mEntityConfidence)
                .setTextClassification(mTextClassification)
                .setExtras(mExtras);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.US,
                "TextSelection {id=%s, startIndex=%d, endIndex=%d, entities=%s}",
                mId, mStartIndex, mEndIndex, mEntityConfidence);
    }

    /**
     * Builder used to build {@link TextSelection} objects.
     */
    public static final class Builder {

        private final int mStartIndex;
        private final int mEndIndex;
        private final Map<String, Float> mEntityConfidence = new ArrayMap<>();
        @Nullable private String mId;
        @Nullable
        private TextClassification mTextClassification;
        @Nullable
        private Bundle mExtras;

        /**
         * Creates a builder used to build {@link TextSelection} objects.
         *
         * @param startIndex the start index of the text selection.
         * @param endIndex the end index of the text selection. Must be greater than startIndex
         */
        public Builder(@IntRange(from = 0) int startIndex, @IntRange(from = 0) int endIndex) {
            Preconditions.checkArgument(startIndex >= 0);
            Preconditions.checkArgument(endIndex > startIndex);
            mStartIndex = startIndex;
            mEndIndex = endIndex;
        }

        /**
         * Sets an entity type for the classified text and assigns a confidence score.
         *
         * @param confidenceScore a value from 0 (low confidence) to 1 (high confidence).
         *      0 implies the entity does not exist for the classified text.
         *      Values greater than 1 are clamped to 1.
         */
        @NonNull
        public Builder setEntityType(
                @NonNull @EntityType String type,
                @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            Objects.requireNonNull(type);
            mEntityConfidence.put(type, confidenceScore);
            return this;
        }

        Builder setEntityConfidence(EntityConfidence scores) {
            mEntityConfidence.clear();
            mEntityConfidence.putAll(scores.toMap());
            return this;
        }

        /**
         * Sets an id for the TextSelection object.
         */
        @NonNull
        public Builder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        /**
         * Sets the text classification result of the suggested selection. If
         * {@link Request#shouldIncludeTextClassification()} is {@code true}, set this value.
         * Otherwise this value may be set to null. The intention of this method is to avoid
         * doing expensive work if the client is not interested in such result.
         *
         * @return this builder
         * @see Request#shouldIncludeTextClassification()
         */
        @NonNull
        public Builder setTextClassification(@Nullable TextClassification textClassification) {
            mTextClassification = textClassification;
            return this;
        }

        /**
         * Sets the extended data.
         *
         * @return this builder
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds and returns {@link TextSelection} object.
         */
        @NonNull
        public TextSelection build() {
            return new TextSelection(
                    mStartIndex, mEndIndex, mEntityConfidence, mId,
                    mTextClassification, mExtras == null ? Bundle.EMPTY : mExtras);
        }
    }

    /**
     * A request object for generating TextSelection.
     */
    public static final class Request implements Parcelable {

        private final CharSequence mText;
        private final int mStartIndex;
        private final int mEndIndex;
        @Nullable private final LocaleList mDefaultLocales;
        private final boolean mDarkLaunchAllowed;
        private final boolean mIncludeTextClassification;
        private final Bundle mExtras;
        @Nullable private SystemTextClassifierMetadata mSystemTcMetadata;

        private Request(
                CharSequence text,
                int startIndex,
                int endIndex,
                LocaleList defaultLocales,
                boolean darkLaunchAllowed,
                boolean includeTextClassification,
                Bundle extras) {
            mText = text;
            mStartIndex = startIndex;
            mEndIndex = endIndex;
            mDefaultLocales = defaultLocales;
            mDarkLaunchAllowed = darkLaunchAllowed;
            mIncludeTextClassification = includeTextClassification;
            mExtras = extras;
        }

        /**
         * Returns the text providing context for the selected text (which is specified by the
         * sub sequence starting at startIndex and ending at endIndex).
         */
        @NonNull
        public CharSequence getText() {
            return mText;
        }

        /**
         * Returns start index of the selected part of text.
         */
        @IntRange(from = 0)
        public int getStartIndex() {
            return mStartIndex;
        }

        /**
         * Returns end index of the selected part of text.
         */
        @IntRange(from = 0)
        public int getEndIndex() {
            return mEndIndex;
        }

        /**
         * Returns true if the TextClassifier should return selection suggestions when "dark
         * launched". Otherwise, returns false.
         *
         * @hide
         */
        public boolean isDarkLaunchAllowed() {
            return mDarkLaunchAllowed;
        }

        /**
         * @return ordered list of locale preferences that can be used to disambiguate the
         * provided text.
         */
        @Nullable
        public LocaleList getDefaultLocales() {
            return mDefaultLocales;
        }

        /**
         * Returns the name of the package that sent this request.
         * This returns {@code null} if no calling package name is set.
         */
        @Nullable
        public String getCallingPackageName() {
            return mSystemTcMetadata != null ? mSystemTcMetadata.getCallingPackageName() : null;
        }

        /**
         * Sets the information about the {@link SystemTextClassifier} that sent this request.
         *
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void setSystemTextClassifierMetadata(
                @Nullable SystemTextClassifierMetadata systemTcMetadata) {
            mSystemTcMetadata = systemTcMetadata;
        }

        /**
         * Returns the information about the {@link SystemTextClassifier} that sent this request.
         *
         * @hide
         */
        @Nullable
        public SystemTextClassifierMetadata getSystemTextClassifierMetadata() {
            return mSystemTcMetadata;
        }

        /**
         * Returns true if the client wants the text classifier to classify the text as well and
         * include a {@link TextClassification} object in the result.
         */
        public boolean shouldIncludeTextClassification() {
            return mIncludeTextClassification;
        }

        /**
         * Returns the extended data.
         *
         * <p><b>NOTE: </b>Do not modify this bundle.
         */
        @NonNull
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * A builder for building TextSelection requests.
         */
        public static final class Builder {

            private final CharSequence mText;
            private final int mStartIndex;
            private final int mEndIndex;
            @Nullable private LocaleList mDefaultLocales;
            private boolean mDarkLaunchAllowed;
            private boolean mIncludeTextClassification;
            private Bundle mExtras;

            /**
             * @param text text providing context for the selected text (which is specified by the
             *      sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
             * @param startIndex start index of the selected part of text
             * @param endIndex end index of the selected part of text
             */
            public Builder(
                    @NonNull CharSequence text,
                    @IntRange(from = 0) int startIndex,
                    @IntRange(from = 0) int endIndex) {
                Utils.checkArgument(text, startIndex, endIndex);
                mText = text;
                mStartIndex = startIndex;
                mEndIndex = endIndex;
            }

            /**
             * @param defaultLocales ordered list of locale preferences that may be used to
             *      disambiguate the provided text. If no locale preferences exist, set this to null
             *      or an empty locale list.
             *
             * @return this builder.
             */
            @NonNull
            public Builder setDefaultLocales(@Nullable LocaleList defaultLocales) {
                mDefaultLocales = defaultLocales;
                return this;
            }

            /**
             * @param allowed whether or not the TextClassifier should return selection suggestions
             *      when "dark launched". When a TextClassifier is dark launched, it can suggest
             *      selection changes that should not be used to actually change the user's
             *      selection. Instead, the suggested selection is logged, compared with the user's
             *      selection interaction, and used to generate quality metrics for the
             *      TextClassifier. Not parceled.
             *
             * @return this builder.
             * @hide
             */
            @NonNull
            public Builder setDarkLaunchAllowed(boolean allowed) {
                mDarkLaunchAllowed = allowed;
                return this;
            }

            /**
             * @param includeTextClassification If true, suggests the TextClassifier to classify the
             *     text in the suggested selection span and include a TextClassification object in
             *     the result. The TextClassifier may not support this and in which case,
             *     {@link TextSelection#getTextClassification()} returns {@code null}.
             *
             * @return this builder.
             * @see TextSelection#getTextClassification()
             */
            @NonNull
            public Builder setIncludeTextClassification(boolean includeTextClassification) {
                mIncludeTextClassification = includeTextClassification;
                return this;
            }

            /**
             * Sets the extended data.
             *
             * @return this builder
             */
            @NonNull
            public Builder setExtras(@Nullable Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Builds and returns the request object.
             */
            @NonNull
            public Request build() {
                return new Request(new SpannedString(mText), mStartIndex, mEndIndex,
                        mDefaultLocales, mDarkLaunchAllowed,
                        mIncludeTextClassification,
                        mExtras == null ? Bundle.EMPTY : mExtras);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeCharSequence(mText);
            dest.writeInt(mStartIndex);
            dest.writeInt(mEndIndex);
            dest.writeParcelable(mDefaultLocales, flags);
            dest.writeBundle(mExtras);
            dest.writeParcelable(mSystemTcMetadata, flags);
            dest.writeBoolean(mIncludeTextClassification);
        }

        private static Request readFromParcel(Parcel in) {
            final CharSequence text = in.readCharSequence();
            final int startIndex = in.readInt();
            final int endIndex = in.readInt();
            final LocaleList defaultLocales = in.readParcelable(null, android.os.LocaleList.class);
            final Bundle extras = in.readBundle();
            final SystemTextClassifierMetadata systemTcMetadata = in.readParcelable(null, android.view.textclassifier.SystemTextClassifierMetadata.class);
            final boolean includeTextClassification = in.readBoolean();

            final Request request = new Request(text, startIndex, endIndex, defaultLocales,
                    /* darkLaunchAllowed= */ false, includeTextClassification, extras);
            request.setSystemTextClassifierMetadata(systemTcMetadata);
            return request;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Request> CREATOR =
                new Parcelable.Creator<Request>() {
                    @Override
                    public Request createFromParcel(Parcel in) {
                        return readFromParcel(in);
                    }

                    @Override
                    public Request[] newArray(int size) {
                        return new Request[size];
                    }
                };
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStartIndex);
        dest.writeInt(mEndIndex);
        mEntityConfidence.writeToParcel(dest, flags);
        dest.writeString(mId);
        dest.writeBundle(mExtras);
        dest.writeParcelable(mTextClassification, flags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TextSelection> CREATOR =
            new Parcelable.Creator<TextSelection>() {
                @Override
                public TextSelection createFromParcel(Parcel in) {
                    return new TextSelection(in);
                }

                @Override
                public TextSelection[] newArray(int size) {
                    return new TextSelection[size];
                }
            };

    private TextSelection(Parcel in) {
        mStartIndex = in.readInt();
        mEndIndex = in.readInt();
        mEntityConfidence = EntityConfidence.CREATOR.createFromParcel(in);
        mId = in.readString();
        mExtras = in.readBundle();
        mTextClassification = in.readParcelable(TextClassification.class.getClassLoader(), android.view.textclassifier.TextClassification.class);
    }
}
