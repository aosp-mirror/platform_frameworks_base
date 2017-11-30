/*
 * Copyright 2017 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.LocaleList;
import android.text.SpannableString;
import android.text.style.ClickableSpan;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A collection of links, representing subsequences of text and the entity types (phone number,
 * address, url, etc) they may be.
 */
public final class TextLinks {
    private final String mFullText;
    private final List<TextLink> mLinks;

    private TextLinks(String fullText, Collection<TextLink> links) {
        mFullText = fullText;
        mLinks = Collections.unmodifiableList(new ArrayList<>(links));
    }

    /**
     * Returns an unmodifiable Collection of the links.
     */
    public Collection<TextLink> getLinks() {
        return mLinks;
    }

    /**
     * Annotates the given text with the generated links. It will fail if the provided text doesn't
     * match the original text used to crete the TextLinks.
     *
     * @param text the text to apply the links to. Must match the original text.
     * @param spanFactory a factory to generate spans from TextLinks. Will use a default if null.
     *
     * @return Success or failure.
     */
    public boolean apply(
            @NonNull SpannableString text,
            @Nullable Function<TextLink, ClickableSpan> spanFactory) {
        Preconditions.checkNotNull(text);
        if (!mFullText.equals(text.toString())) {
            return false;
        }

        if (spanFactory == null) {
            spanFactory = DEFAULT_SPAN_FACTORY;
        }
        for (TextLink link : mLinks) {
            final ClickableSpan span = spanFactory.apply(link);
            if (span != null) {
                text.setSpan(span, link.getStart(), link.getEnd(), 0);
            }
        }
        return true;
    }

    /**
     * A link, identifying a substring of text and possible entity types for it.
     */
    public static final class TextLink {
        private final EntityConfidence<String> mEntityScores;
        private final String mOriginalText;
        private final int mStart;
        private final int mEnd;

        /**
         * Create a new TextLink.
         *
         * @throws IllegalArgumentException if entityScores is null or empty.
         */
        public TextLink(String originalText, int start, int end, Map<String, Float> entityScores) {
            Preconditions.checkNotNull(originalText);
            Preconditions.checkNotNull(entityScores);
            Preconditions.checkArgument(!entityScores.isEmpty());
            Preconditions.checkArgument(start <= end);
            mOriginalText = originalText;
            mStart = start;
            mEnd = end;
            mEntityScores = new EntityConfidence<>();

            for (Map.Entry<String, Float> entry : entityScores.entrySet()) {
                mEntityScores.setEntityType(entry.getKey(), entry.getValue());
            }
        }

        /**
         * Returns the start index of this link in the original text.
         *
         * @return the start index.
         */
        public int getStart() {
            return mStart;
        }

        /**
         * Returns the end index of this link in the original text.
         *
         * @return the end index.
         */
        public int getEnd() {
            return mEnd;
        }

        /**
         * Returns the number of entity types that have confidence scores.
         *
         * @return the entity count.
         */
        public int getEntityCount() {
            return mEntityScores.getEntities().size();
        }

        /**
         * Returns the entity type at a given index. Entity types are sorted by confidence.
         *
         * @return the entity type at the provided index.
         */
        @NonNull public @TextClassifier.EntityType String getEntity(int index) {
            return mEntityScores.getEntities().get(index);
        }

        /**
         * Returns the confidence score for a particular entity type.
         *
         * @param entityType the entity type.
         */
        public @FloatRange(from = 0.0, to = 1.0) float getConfidenceScore(
                @TextClassifier.EntityType String entityType) {
            return mEntityScores.getConfidenceScore(entityType);
        }
    }

    /**
     * Optional input parameters for generating TextLinks.
     */
    public static final class Options {

        private LocaleList mDefaultLocales;

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
    }

    /**
     * A function to create spans from TextLinks.
     *
     * Applies only to TextViews.
     * We can hide this until we are convinced we want it to be part of the public API.
     *
     * @hide
     */
    public static final Function<TextLink, ClickableSpan> DEFAULT_SPAN_FACTORY =
            textLink -> {
                // TODO: Implement.
                throw new UnsupportedOperationException("Not yet implemented");
            };

    /**
     * A builder to construct a TextLinks instance.
     */
    public static final class Builder {
        private final String mFullText;
        private final Collection<TextLink> mLinks;

        /**
         * Create a new TextLinks.Builder.
         *
         * @param fullText The full text that links will be added to.
         */
        public Builder(@NonNull String fullText) {
            mFullText = Preconditions.checkNotNull(fullText);
            mLinks = new ArrayList<>();
        }

        /**
         * Adds a TextLink.
         *
         * @return this instance.
         */
        public Builder addLink(TextLink link) {
            Preconditions.checkNotNull(link);
            mLinks.add(link);
            return this;
        }

        /**
         * Constructs a TextLinks instance.
         *
         * @return the constructed TextLinks.
         */
        public TextLinks build() {
            return new TextLinks(mFullText, mLinks);
        }
    }
}
