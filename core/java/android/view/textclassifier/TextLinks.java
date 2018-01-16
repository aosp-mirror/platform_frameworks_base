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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

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
public final class TextLinks implements Parcelable {
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mFullText);
        dest.writeTypedList(mLinks);
    }

    public static final Parcelable.Creator<TextLinks> CREATOR =
            new Parcelable.Creator<TextLinks>() {
                @Override
                public TextLinks createFromParcel(Parcel in) {
                    return new TextLinks(in);
                }

                @Override
                public TextLinks[] newArray(int size) {
                    return new TextLinks[size];
                }
            };

    private TextLinks(Parcel in) {
        mFullText = in.readString();
        mLinks = in.createTypedArrayList(TextLink.CREATOR);
    }

    /**
     * A link, identifying a substring of text and possible entity types for it.
     */
    public static final class TextLink implements Parcelable {
        private final EntityConfidence mEntityScores;
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
            mEntityScores = new EntityConfidence(entityScores);
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

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mEntityScores.writeToParcel(dest, flags);
            dest.writeString(mOriginalText);
            dest.writeInt(mStart);
            dest.writeInt(mEnd);
        }

        public static final Parcelable.Creator<TextLink> CREATOR =
                new Parcelable.Creator<TextLink>() {
                    @Override
                    public TextLink createFromParcel(Parcel in) {
                        return new TextLink(in);
                    }

                    @Override
                    public TextLink[] newArray(int size) {
                        return new TextLink[size];
                    }
                };

        private TextLink(Parcel in) {
            mEntityScores = EntityConfidence.CREATOR.createFromParcel(in);
            mOriginalText = in.readString();
            mStart = in.readInt();
            mEnd = in.readInt();
        }
    }

    /**
     * Optional input parameters for generating TextLinks.
     */
    public static final class Options implements Parcelable {

        private LocaleList mDefaultLocales;
        private TextClassifier.EntityConfig mEntityConfig;

        public Options() {}

        /**
         * @param defaultLocales ordered list of locale preferences that may be used to
         *                       disambiguate the provided text. If no locale preferences exist,
         *                       set this to null or an empty locale list.
         */
        public Options setDefaultLocales(@Nullable LocaleList defaultLocales) {
            mDefaultLocales = defaultLocales;
            return this;
        }

        /**
         * Sets the entity configuration to use. This determines what types of entities the
         * TextClassifier will look for.
         *
         * @param entityConfig EntityConfig to use
         */
        public Options setEntityConfig(@Nullable TextClassifier.EntityConfig entityConfig) {
            mEntityConfig = entityConfig;
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
         * @return The config representing the set of entities to look for.
         * @see #setEntityConfig(TextClassifier.EntityConfig)
         */
        @Nullable
        public TextClassifier.EntityConfig getEntityConfig() {
            return mEntityConfig;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mDefaultLocales != null ? 1 : 0);
            if (mDefaultLocales != null) {
                mDefaultLocales.writeToParcel(dest, flags);
            }
            dest.writeInt(mEntityConfig != null ? 1 : 0);
            if (mEntityConfig != null) {
                mEntityConfig.writeToParcel(dest, flags);
            }
        }

        public static final Parcelable.Creator<Options> CREATOR =
                new Parcelable.Creator<Options>() {
                    @Override
                    public Options createFromParcel(Parcel in) {
                        return new Options(in);
                    }

                    @Override
                    public Options[] newArray(int size) {
                        return new Options[size];
                    }
                };

        private Options(Parcel in) {
            if (in.readInt() > 0) {
                mDefaultLocales = LocaleList.CREATOR.createFromParcel(in);
            }
            if (in.readInt() > 0) {
                mEntityConfig = TextClassifier.EntityConfig.CREATOR.createFromParcel(in);
            }
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
            textLink -> new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (widget instanceof TextView) {
                        final TextView textView = (TextView) widget;
                        textView.requestActionMode(textLink);
                    }
                }
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
