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
import android.os.LocaleList;
import android.view.textclassifier.TextClassifier.EntityType;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Locale;

/**
 * Information about where text selection should be.
 */
public final class TextSelection {

    private final int mStartIndex;
    private final int mEndIndex;
    @NonNull private final EntityConfidence<String> mEntityConfidence;
    @NonNull private final List<String> mEntities;
    @NonNull private final String mLogSource;
    @NonNull private final String mVersionInfo;

    private TextSelection(
            int startIndex, int endIndex, @NonNull EntityConfidence<String> entityConfidence,
            @NonNull String logSource, @NonNull String versionInfo) {
        mStartIndex = startIndex;
        mEndIndex = endIndex;
        mEntityConfidence = new EntityConfidence<>(entityConfidence);
        mEntities = mEntityConfidence.getEntities();
        mLogSource = logSource;
        mVersionInfo = versionInfo;
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
        return mEntities.size();
    }

    /**
     * Returns the entity at the specified index. Entities are ordered from high confidence
     * to low confidence.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getEntityCount() for the number of entities available.
     */
    @NonNull
    public @EntityType String getEntity(int index) {
        return mEntities.get(index);
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
     * Returns a tag for the source classifier used to generate this result.
     * @hide
     */
    @NonNull
    public String getSourceClassifier() {
        return mLogSource;
    }

    /**
     * Returns information about the classifier model used to generate this TextSelection.
     * @hide
     */
    @NonNull
    public String getVersionInfo() {
        return mVersionInfo;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "TextSelection {%d, %d, %s}", mStartIndex, mEndIndex, mEntityConfidence);
    }

    /**
     * Builder used to build {@link TextSelection} objects.
     */
    public static final class Builder {

        private final int mStartIndex;
        private final int mEndIndex;
        @NonNull private final EntityConfidence<String> mEntityConfidence =
                new EntityConfidence<>();
        @NonNull private String mLogSource = "";
        @NonNull private String mVersionInfo = "";

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
        public Builder setEntityType(
                @NonNull @EntityType String type,
                @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            mEntityConfidence.setEntityType(type, confidenceScore);
            return this;
        }

        /**
         * Sets a tag for the source classifier used to generate this result.
         * @hide
         */
        Builder setLogSource(@NonNull String logSource) {
            mLogSource = Preconditions.checkNotNull(logSource);
            return this;
        }

        /**
         * Sets information about the classifier model used to generate this TextSelection.
         * @hide
         */
        Builder setVersionInfo(@NonNull String versionInfo) {
            mVersionInfo = Preconditions.checkNotNull(versionInfo);
            return this;
        }

        /**
         * Builds and returns {@link TextSelection} object.
         */
        public TextSelection build() {
            return new TextSelection(
                    mStartIndex, mEndIndex, mEntityConfidence, mLogSource, mVersionInfo);
        }
    }

    /**
     * Optional input parameters for generating TextSelection.
     */
    public static final class Options {

        private LocaleList mDefaultLocales;
        private boolean mDarkLaunchAllowed;

        /**
         * @param defaultLocales ordered list of locale preferences that may be used to disambiguate
         *      the provided text. If no locale preferences exist, set this to null or an empty
         *      locale list.
         */
        public Options setDefaultLocales(@Nullable LocaleList defaultLocales) {
            mDefaultLocales = defaultLocales;
            return this;
        }

        /**
         * @return ordered list of locale preferences that can be used to disambiguate
         *      the provided text.
         */
        @Nullable
        public LocaleList getDefaultLocales() {
            return mDefaultLocales;
        }

        /**
         * @param allowed whether or not the TextClassifier should return selection suggestions
         *      when "dark launched". When a TextClassifier is dark launched, it can suggest
         *      selection changes that should not be used to actually change the user's selection.
         *      Instead, the suggested selection is logged, compared with the user's selection
         *      interaction, and used to generate quality metrics for the TextClassifier.
         *
         * @hide
         */
        public void setDarkLaunchAllowed(boolean allowed) {
            mDarkLaunchAllowed = allowed;
        }

        /**
         * Returns true if the TextClassifier should return selection suggestions when
         * "dark launched". Otherwise, returns false.
         *
         * @hide
         */
        public boolean isDarkLaunchAllowed() {
            return mDarkLaunchAllowed;
        }
    }
}
