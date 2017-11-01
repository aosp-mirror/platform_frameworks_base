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

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Locale;

/**
 * Specifies detected languages for a section of text indicated by a start and end index.
 * @hide
 */
public final class TextLanguage {

    private final int mStartIndex;
    private final int mEndIndex;
    @NonNull private final EntityConfidence<Locale> mLanguageConfidence;
    @NonNull private final List<Locale> mLanguages;

    private TextLanguage(
            int startIndex, int endIndex, @NonNull EntityConfidence<Locale> languageConfidence) {
        mStartIndex = startIndex;
        mEndIndex = endIndex;
        mLanguageConfidence = new EntityConfidence<>(languageConfidence);
        mLanguages = mLanguageConfidence.getEntities();
    }

    /**
     * Returns the start index of the detected languages in the text provided to generate this
     * object.
     */
    public int getStartIndex() {
        return mStartIndex;
    }

    /**
     * Returns the end index of the detected languages in the text provided to generate this object.
     */
    public int getEndIndex() {
        return mEndIndex;
    }

    /**
     * Returns the number of languages found in the classified text.
     */
    @IntRange(from = 0)
    public int getLanguageCount() {
        return mLanguages.size();
    }

    /**
     * Returns the language locale at the specified index.
     * Language locales are ordered from high confidence to low confidence.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getLanguageCount() for the number of language locales available.
     */
    @NonNull
    public Locale getLanguage(int index) {
        return mLanguages.get(index);
    }

    /**
     * Returns the confidence score for the specified language. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the language was
     * not found for the classified text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(@Nullable Locale language) {
        return mLanguageConfidence.getConfidenceScore(language);
    }

    @Override
    public String toString() {
        return String.format("TextLanguage {%d, %d, %s}",
                mStartIndex, mEndIndex, mLanguageConfidence);
    }

    /**
     * Builder to build {@link TextLanguage} objects.
     */
    public static final class Builder {

        private final int mStartIndex;
        private final int mEndIndex;
        @NonNull private final EntityConfidence<Locale> mLanguageConfidence =
                new EntityConfidence<>();

        /**
         * Creates a builder to build {@link TextLanguage} objects.
         *
         * @param startIndex the start index of the detected languages in the text provided
         *      to generate the result
         * @param endIndex the end index of the detected languages in the text provided
         *      to generate the result. Must be greater than startIndex
         */
        public Builder(@IntRange(from = 0) int startIndex, @IntRange(from = 0) int endIndex) {
            Preconditions.checkArgument(startIndex >= 0);
            Preconditions.checkArgument(endIndex > startIndex);
            mStartIndex = startIndex;
            mEndIndex = endIndex;
        }

        /**
         * Sets a language locale with the associated confidence score.
         */
        public Builder setLanguage(
                @NonNull Locale locale, @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            mLanguageConfidence.setEntityType(locale, confidenceScore);
            return this;
        }

        /**
         * Builds and returns a {@link TextLanguage}.
         */
        public TextLanguage build() {
            return new TextLanguage(mStartIndex, mEndIndex, mLanguageConfidence);
        }
    }
}
