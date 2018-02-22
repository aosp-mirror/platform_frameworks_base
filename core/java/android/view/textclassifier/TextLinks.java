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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Spannable;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.text.util.Linkify.LinkifyMask;
import android.view.View;
import android.view.textclassifier.TextClassifier.EntityType;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    /**
     * Return status of an attempt to apply TextLinks to text.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_LINKS_APPLIED, STATUS_NO_LINKS_FOUND, STATUS_NO_LINKS_APPLIED,
            STATUS_DIFFERENT_TEXT})
    public @interface Status {}

    /** Links were successfully applied to the text. */
    public static final int STATUS_LINKS_APPLIED = 0;

    /** No links exist to apply to text. Links count is zero. */
    public static final int STATUS_NO_LINKS_FOUND = 1;

    /** No links applied to text. The links were filtered out. */
    public static final int STATUS_NO_LINKS_APPLIED = 2;

    /** The specified text does not match the text used to generate the links. */
    public static final int STATUS_DIFFERENT_TEXT = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({APPLY_STRATEGY_IGNORE, APPLY_STRATEGY_REPLACE})
    public @interface ApplyStrategy {}

    /**
      * Do not replace {@link ClickableSpan}s that exist where the {@link TextLinkSpan} needs to
      * be applied to. Do not apply the TextLinkSpan.
      */
    public static final int APPLY_STRATEGY_IGNORE = 0;

    /**
      * Replace any {@link ClickableSpan}s that exist where the {@link TextLinkSpan} needs to be
      * applied to.
      */
    public static final int APPLY_STRATEGY_REPLACE = 1;

    private final String mFullText;
    private final List<TextLink> mLinks;

    private TextLinks(String fullText, ArrayList<TextLink> links) {
        mFullText = fullText;
        mLinks = Collections.unmodifiableList(links);
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
     * @param text the text to apply the links to. Must match the original text
     * @param applyStrategy strategy for resolving link conflicts
     * @param spanFactory a factory to generate spans from TextLinks. Will use a default if null
     * @param allowPrefix whether to allow applying links only to a prefix of the text.
     *
     * @return a status code indicating whether or not the links were successfully applied
     *
     * @hide
     */
    @Status
    public int apply(
            @NonNull Spannable text,
            @ApplyStrategy int applyStrategy,
            @Nullable Function<TextLink, TextLinkSpan> spanFactory,
            boolean allowPrefix) {
        Preconditions.checkNotNull(text);
        checkValidApplyStrategy(applyStrategy);
        final String textString = text.toString();
        if (!mFullText.equals(textString) && !(allowPrefix && textString.startsWith(mFullText))) {
            return STATUS_DIFFERENT_TEXT;
        }
        if (mLinks.isEmpty()) {
            return STATUS_NO_LINKS_FOUND;
        }

        if (spanFactory == null) {
            spanFactory = DEFAULT_SPAN_FACTORY;
        }
        int applyCount = 0;
        for (TextLink link : mLinks) {
            final TextLinkSpan span = spanFactory.apply(link);
            if (span != null) {
                final ClickableSpan[] existingSpans = text.getSpans(
                        link.getStart(), link.getEnd(), ClickableSpan.class);
                if (existingSpans.length > 0) {
                    if (applyStrategy == APPLY_STRATEGY_REPLACE) {
                        for (ClickableSpan existingSpan : existingSpans) {
                            text.removeSpan(existingSpan);
                        }
                        text.setSpan(span, link.getStart(), link.getEnd(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        applyCount++;
                    }
                } else {
                    text.setSpan(span, link.getStart(), link.getEnd(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    applyCount++;
                }
            }
        }
        if (applyCount == 0) {
            return STATUS_NO_LINKS_APPLIED;
        }
        return STATUS_LINKS_APPLIED;
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
        private final int mStart;
        private final int mEnd;

        /**
         * Create a new TextLink.
         *
         * @param start The start index of the identified subsequence
         * @param end The end index of the identified subsequence
         * @param entityScores A mapping of entity type to confidence score
         *
         * @throws IllegalArgumentException if entityScores is null or empty
         */
        TextLink(int start, int end, Map<String, Float> entityScores) {
            Preconditions.checkNotNull(entityScores);
            Preconditions.checkArgument(!entityScores.isEmpty());
            Preconditions.checkArgument(start <= end);
            mStart = start;
            mEnd = end;
            mEntityScores = new EntityConfidence(entityScores);
        }

        /**
         * Returns the start index of this link in the original text.
         *
         * @return the start index
         */
        public int getStart() {
            return mStart;
        }

        /**
         * Returns the end index of this link in the original text.
         *
         * @return the end index
         */
        public int getEnd() {
            return mEnd;
        }

        /**
         * Returns the number of entity types that have confidence scores.
         *
         * @return the entity count
         */
        public int getEntityCount() {
            return mEntityScores.getEntities().size();
        }

        /**
         * Returns the entity type at a given index. Entity types are sorted by confidence.
         *
         * @return the entity type at the provided index
         */
        @NonNull public @EntityType String getEntity(int index) {
            return mEntityScores.getEntities().get(index);
        }

        /**
         * Returns the confidence score for a particular entity type.
         *
         * @param entityType the entity type
         */
        public @FloatRange(from = 0.0, to = 1.0) float getConfidenceScore(
                @EntityType String entityType) {
            return mEntityScores.getConfidenceScore(entityType);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mEntityScores.writeToParcel(dest, flags);
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

        private @ApplyStrategy int mApplyStrategy;
        private Function<TextLink, TextLinkSpan> mSpanFactory;

        private String mCallingPackageName;

        /**
         * Returns a new options object based on the specified link mask.
         */
        public static Options fromLinkMask(@LinkifyMask int mask) {
            final List<String> entitiesToFind = new ArrayList<>();

            if ((mask & Linkify.WEB_URLS) != 0) {
                entitiesToFind.add(TextClassifier.TYPE_URL);
            }
            if ((mask & Linkify.EMAIL_ADDRESSES) != 0) {
                entitiesToFind.add(TextClassifier.TYPE_EMAIL);
            }
            if ((mask & Linkify.PHONE_NUMBERS) != 0) {
                entitiesToFind.add(TextClassifier.TYPE_PHONE);
            }
            if ((mask & Linkify.MAP_ADDRESSES) != 0) {
                entitiesToFind.add(TextClassifier.TYPE_ADDRESS);
            }

            return new Options().setEntityConfig(
                    TextClassifier.EntityConfig.createWithEntityList(entitiesToFind));
        }

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
         * Sets a strategy for resolving conflicts when applying generated links to text that
         * already have links.
         *
         * @throws IllegalArgumentException if applyStrategy is not valid
         *
         * @see #APPLY_STRATEGY_IGNORE
         * @see #APPLY_STRATEGY_REPLACE
         */
        public Options setApplyStrategy(@ApplyStrategy int applyStrategy) {
            checkValidApplyStrategy(applyStrategy);
            mApplyStrategy = applyStrategy;
            return this;
        }

        /**
         * Sets a factory for converting a TextLink to a TextLinkSpan.
         *
         * <p><strong>Note: </strong>This is not parceled over IPC.
         */
        public Options setSpanFactory(@Nullable Function<TextLink, TextLinkSpan> spanFactory) {
            mSpanFactory = spanFactory;
            return this;
        }

        /**
         * Sets the name of the package that requested the links to get generated.
         * @hide
         */
        public Options setCallingPackageName(@Nullable String callingPackageName) {
            mCallingPackageName = callingPackageName;
            return this;
        }

        /**
         * @return ordered list of locale preferences that can be used to disambiguate
         *      the provided text
         */
        @Nullable
        public LocaleList getDefaultLocales() {
            return mDefaultLocales;
        }

        /**
         * @return The config representing the set of entities to look for
         * @see #setEntityConfig(TextClassifier.EntityConfig)
         */
        @Nullable
        public TextClassifier.EntityConfig getEntityConfig() {
            return mEntityConfig;
        }

        /**
         * @return the strategy for resolving conflictswhen applying generated links to text that
         * already have links
         *
         * @see #APPLY_STRATEGY_IGNORE
         * @see #APPLY_STRATEGY_REPLACE
         */
        @ApplyStrategy
        public int getApplyStrategy() {
            return mApplyStrategy;
        }

        /**
         * Returns a factory for converting a TextLink to a TextLinkSpan.
         *
         * <p><strong>Note: </strong>This is not parcelable and will always return null if read
         *      from a parcel
         */
        @Nullable
        public Function<TextLink, TextLinkSpan> getSpanFactory() {
            return mSpanFactory;
        }

        /**
         * @return the name of the package that requested the links to get generated.
         * TODO: make available as system API
         * @hide
         */
        @Nullable
        public String getCallingPackageName() {
            return mCallingPackageName;
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
            dest.writeInt(mApplyStrategy);
            dest.writeString(mCallingPackageName);
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
            mApplyStrategy = in.readInt();
            mCallingPackageName = in.readString();
        }
    }

    /**
     * A function to create spans from TextLinks.
     */
    private static final Function<TextLink, TextLinkSpan> DEFAULT_SPAN_FACTORY =
            textLink -> new TextLinkSpan(textLink);

    /**
     * A ClickableSpan for a TextLink.
     *
     * <p>Applies only to TextViews.
     */
    public static class TextLinkSpan extends ClickableSpan {

        private final TextLink mTextLink;

        public TextLinkSpan(@Nullable TextLink textLink) {
            mTextLink = textLink;
        }

        @Override
        public void onClick(View widget) {
            if (widget instanceof TextView) {
                final TextView textView = (TextView) widget;
                textView.requestActionMode(mTextLink);
            }
        }

        public final TextLink getTextLink() {
            return mTextLink;
        }
    }

    /**
     * A builder to construct a TextLinks instance.
     */
    public static final class Builder {
        private final String mFullText;
        private final ArrayList<TextLink> mLinks;

        /**
         * Create a new TextLinks.Builder.
         *
         * @param fullText The full text to annotate with links
         */
        public Builder(@NonNull String fullText) {
            mFullText = Preconditions.checkNotNull(fullText);
            mLinks = new ArrayList<>();
        }

        /**
         * Adds a TextLink.
         *
         * @return this instance
         *
         * @throws IllegalArgumentException if entityScores is null or empty.
         */
        public Builder addLink(int start, int end, Map<String, Float> entityScores) {
            mLinks.add(new TextLink(start, end, entityScores));
            return this;
        }

        /**
         * Removes all {@link TextLink}s.
         */
        public Builder clearTextLinks() {
            mLinks.clear();
            return this;
        }

        /**
         * Constructs a TextLinks instance.
         *
         * @return the constructed TextLinks
         */
        public TextLinks build() {
            return new TextLinks(mFullText, mLinks);
        }
    }

    /**
     * @throws IllegalArgumentException if the value is invalid
     */
    private static void checkValidApplyStrategy(int applyStrategy) {
        if (applyStrategy != APPLY_STRATEGY_IGNORE && applyStrategy != APPLY_STRATEGY_REPLACE) {
            throw new IllegalArgumentException(
                    "Invalid apply strategy. See TextLinks.ApplyStrategy for options.");
        }
    }
}
