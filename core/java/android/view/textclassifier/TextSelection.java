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

/**
 * Information about where text selection should be.
 */
public final class TextSelection implements Parcelable {

    private final int mStartIndex;
    private final int mEndIndex;
    private final EntityConfidence mEntityConfidence;
    @Nullable private final String mId;
    private final Bundle mExtras;

    private TextSelection(
            int startIndex, int endIndex, Map<String, Float> entityConfidence, String id,
            Bundle extras) {
        mStartIndex = startIndex;
        mEndIndex = endIndex;
        mEntityConfidence = new EntityConfidence(entityConfidence);
        mId = id;
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
     * Returns the extended data.
     *
     * <p><b>NOTE: </b>Each call to this method returns a new bundle copy so clients should
     * prefer to hold a reference to the returned bundle rather than frequently calling this
     * method.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras.deepCopy();
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
            Preconditions.checkNotNull(type);
            mEntityConfidence.put(type, confidenceScore);
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
         * Sets the extended data.
         *
         * @return this builder
         */
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
                    mExtras == null ? Bundle.EMPTY : mExtras.deepCopy());
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
        private final Bundle mExtras;
        @Nullable private String mCallingPackageName;

        private Request(
                CharSequence text,
                int startIndex,
                int endIndex,
                LocaleList defaultLocales,
                boolean darkLaunchAllowed,
                Bundle extras) {
            mText = text;
            mStartIndex = startIndex;
            mEndIndex = endIndex;
            mDefaultLocales = defaultLocales;
            mDarkLaunchAllowed = darkLaunchAllowed;
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
         * Sets the name of the package that is sending this request.
         * <p>
         * Package-private for SystemTextClassifier's use.
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void setCallingPackageName(@Nullable String callingPackageName) {
            mCallingPackageName = callingPackageName;
        }

        /**
         * Returns the name of the package that sent this request.
         * This returns {@code null} if no calling package name is set.
         */
        @Nullable
        public String getCallingPackageName() {
            return mCallingPackageName;
        }

        /**
         * Returns the extended data.
         *
         * <p><b>NOTE: </b>Each call to this method returns a new bundle copy so clients should
         * prefer to hold a reference to the returned bundle rather than frequently calling this
         * method.
         */
        @NonNull
        public Bundle getExtras() {
            return mExtras.deepCopy();
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
             * Sets the extended data.
             *
             * @return this builder
             */
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
                        mExtras == null ? Bundle.EMPTY : mExtras.deepCopy());
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
            dest.writeString(mCallingPackageName);
            dest.writeBundle(mExtras);
        }

        private static Request readFromParcel(Parcel in) {
            final CharSequence text = in.readCharSequence();
            final int startIndex = in.readInt();
            final int endIndex = in.readInt();
            final LocaleList defaultLocales = in.readParcelable(null);
            final String callingPackageName = in.readString();
            final Bundle extras = in.readBundle();

            final Request request = new Request(text, startIndex, endIndex, defaultLocales,
                    /* darkLaunchAllowed= */ false, extras);
            request.setCallingPackageName(callingPackageName);
            return request;
        }

        public static final Parcelable.Creator<Request> CREATOR =
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
    }

    public static final Parcelable.Creator<TextSelection> CREATOR =
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
    }
}
