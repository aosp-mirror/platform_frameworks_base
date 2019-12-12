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
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.textclassifier.TextClassifier.EntityConfig;
import android.view.textclassifier.TextClassifier.EntityType;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
            STATUS_DIFFERENT_TEXT, STATUS_UNSUPPORTED_CHARACTER})
    public @interface Status {}

    /** Links were successfully applied to the text. */
    public static final int STATUS_LINKS_APPLIED = 0;

    /** No links exist to apply to text. Links count is zero. */
    public static final int STATUS_NO_LINKS_FOUND = 1;

    /** No links applied to text. The links were filtered out. */
    public static final int STATUS_NO_LINKS_APPLIED = 2;

    /** The specified text does not match the text used to generate the links. */
    public static final int STATUS_DIFFERENT_TEXT = 3;

    /** The specified text contains unsupported characters. */
    public static final int STATUS_UNSUPPORTED_CHARACTER = 4;

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
    private final Bundle mExtras;

    private TextLinks(String fullText, ArrayList<TextLink> links, Bundle extras) {
        mFullText = fullText;
        mLinks = Collections.unmodifiableList(links);
        mExtras = extras;
    }

    /**
     * Returns the text that was used to generate these links.
     * @hide
     */
    @NonNull
    public String getText() {
        return mFullText;
    }

    /**
     * Returns an unmodifiable Collection of the links.
     */
    @NonNull
    public Collection<TextLink> getLinks() {
        return mLinks;
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
     * Annotates the given text with the generated links. It will fail if the provided text doesn't
     * match the original text used to create the TextLinks.
     *
     * <p><strong>NOTE: </strong>It may be necessary to set a LinkMovementMethod on the TextView
     * widget to properly handle links. See {@link TextView#setMovementMethod(MovementMethod)}
     *
     * @param text the text to apply the links to. Must match the original text
     * @param applyStrategy the apply strategy used to determine how to apply links to text.
     *      e.g {@link TextLinks#APPLY_STRATEGY_IGNORE}
     * @param spanFactory a custom span factory for converting TextLinks to TextLinkSpans.
     *      Set to {@code null} to use the default span factory.
     *
     * @return a status code indicating whether or not the links were successfully applied
     *      e.g. {@link #STATUS_LINKS_APPLIED}
     */
    @Status
    public int apply(
            @NonNull Spannable text,
            @ApplyStrategy int applyStrategy,
            @Nullable Function<TextLink, TextLinkSpan> spanFactory) {
        Preconditions.checkNotNull(text);
        return new TextLinksParams.Builder()
                .setApplyStrategy(applyStrategy)
                .setSpanFactory(spanFactory)
                .build()
                .apply(text, this);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "TextLinks{fullText=%s, links=%s}", mFullText, mLinks);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mFullText);
        dest.writeTypedList(mLinks);
        dest.writeBundle(mExtras);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TextLinks> CREATOR =
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
        mExtras = in.readBundle();
    }

    /**
     * A link, identifying a substring of text and possible entity types for it.
     */
    public static final class TextLink implements Parcelable {
        private final EntityConfidence mEntityScores;
        private final int mStart;
        private final int mEnd;
        private final Bundle mExtras;
        @Nullable private final URLSpan mUrlSpan;

        /**
         * Create a new TextLink.
         *
         * @param start The start index of the identified subsequence
         * @param end The end index of the identified subsequence
         * @param entityConfidence A mapping of entity type to confidence score
         * @param extras A bundle containing custom data related to this TextLink
         * @param urlSpan An optional URLSpan to delegate to. NOTE: Not parcelled
         *
         * @throws IllegalArgumentException if {@code entityConfidence} is null or empty
         * @throws IllegalArgumentException if {@code start} is greater than {@code end}
         */
        private TextLink(int start, int end, @NonNull EntityConfidence entityConfidence,
                @NonNull Bundle extras, @Nullable URLSpan urlSpan) {
            Preconditions.checkNotNull(entityConfidence);
            Preconditions.checkArgument(!entityConfidence.getEntities().isEmpty());
            Preconditions.checkArgument(start <= end);
            Preconditions.checkNotNull(extras);
            mStart = start;
            mEnd = end;
            mEntityScores = entityConfidence;
            mUrlSpan = urlSpan;
            mExtras = extras;
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

        /**
         * Returns a bundle containing custom data related to this TextLink.
         */
        @NonNull
        public Bundle getExtras() {
            return mExtras;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "TextLink{start=%s, end=%s, entityScores=%s, urlSpan=%s}",
                    mStart, mEnd, mEntityScores, mUrlSpan);
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
            dest.writeBundle(mExtras);
        }

        private static TextLink readFromParcel(Parcel in) {
            final EntityConfidence entityConfidence = EntityConfidence.CREATOR.createFromParcel(in);
            final int start = in.readInt();
            final int end = in.readInt();
            final Bundle extras = in.readBundle();
            return new TextLink(start, end, entityConfidence, extras, null /* urlSpan */);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<TextLink> CREATOR =
                new Parcelable.Creator<TextLink>() {
                    @Override
                    public TextLink createFromParcel(Parcel in) {
                        return readFromParcel(in);
                    }

                    @Override
                    public TextLink[] newArray(int size) {
                        return new TextLink[size];
                    }
                };
    }

    /**
     * A request object for generating TextLinks.
     */
    public static final class Request implements Parcelable {

        private final CharSequence mText;
        @Nullable private final LocaleList mDefaultLocales;
        @Nullable private final EntityConfig mEntityConfig;
        private final boolean mLegacyFallback;
        @Nullable private String mCallingPackageName;
        private final Bundle mExtras;
        @UserIdInt
        private int mUserId = UserHandle.USER_NULL;

        private Request(
                CharSequence text,
                LocaleList defaultLocales,
                EntityConfig entityConfig,
                boolean legacyFallback,
                Bundle extras) {
            mText = text;
            mDefaultLocales = defaultLocales;
            mEntityConfig = entityConfig;
            mLegacyFallback = legacyFallback;
            mExtras = extras;
        }

        /**
         * Returns the text to generate links for.
         */
        @NonNull
        public CharSequence getText() {
            return mText;
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
         * @see Builder#setEntityConfig(EntityConfig)
         */
        @Nullable
        public EntityConfig getEntityConfig() {
            return mEntityConfig;
        }

        /**
         * Returns whether the TextClassifier can fallback to legacy links if smart linkify is
         * disabled.
         * <strong>Note: </strong>This is not parcelled.
         * @hide
         */
        public boolean isLegacyFallback() {
            return mLegacyFallback;
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
         * Sets the id of the user that sent this request.
         * <p>
         * Package-private for SystemTextClassifier's use.
         */
        void setUserId(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Returns the id of the user that sent this request.
         * @hide
         */
        @UserIdInt
        public int getUserId() {
            return mUserId;
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
         * A builder for building TextLinks requests.
         */
        public static final class Builder {

            private final CharSequence mText;

            @Nullable private LocaleList mDefaultLocales;
            @Nullable private EntityConfig mEntityConfig;
            private boolean mLegacyFallback = true; // Use legacy fall back by default.
            @Nullable private Bundle mExtras;

            public Builder(@NonNull CharSequence text) {
                mText = Preconditions.checkNotNull(text);
            }

            /**
             * @param defaultLocales ordered list of locale preferences that may be used to
             *                       disambiguate the provided text. If no locale preferences exist,
             *                       set this to null or an empty locale list.
             * @return this builder
             */
            @NonNull
            public Builder setDefaultLocales(@Nullable LocaleList defaultLocales) {
                mDefaultLocales = defaultLocales;
                return this;
            }

            /**
             * Sets the entity configuration to use. This determines what types of entities the
             * TextClassifier will look for.
             * Set to {@code null} for the default entity config and teh TextClassifier will
             * automatically determine what links to generate.
             *
             * @return this builder
             */
            @NonNull
            public Builder setEntityConfig(@Nullable EntityConfig entityConfig) {
                mEntityConfig = entityConfig;
                return this;
            }

            /**
             * Sets whether the TextClassifier can fallback to legacy links if smart linkify is
             * disabled.
             *
             * <p><strong>Note: </strong>This is not parcelled.
             *
             * @return this builder
             * @hide
             */
            @NonNull
            public Builder setLegacyFallback(boolean legacyFallback) {
                mLegacyFallback = legacyFallback;
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
                return new Request(
                        mText, mDefaultLocales, mEntityConfig,
                        mLegacyFallback,
                        mExtras == null ? Bundle.EMPTY : mExtras);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mText.toString());
            dest.writeParcelable(mDefaultLocales, flags);
            dest.writeParcelable(mEntityConfig, flags);
            dest.writeString(mCallingPackageName);
            dest.writeInt(mUserId);
            dest.writeBundle(mExtras);
        }

        private static Request readFromParcel(Parcel in) {
            final String text = in.readString();
            final LocaleList defaultLocales = in.readParcelable(null);
            final EntityConfig entityConfig = in.readParcelable(null);
            final String callingPackageName = in.readString();
            final int userId = in.readInt();
            final Bundle extras = in.readBundle();

            final Request request = new Request(text, defaultLocales, entityConfig,
                    /* legacyFallback= */ true, extras);
            request.setCallingPackageName(callingPackageName);
            request.setUserId(userId);
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

    /**
     * A ClickableSpan for a TextLink.
     *
     * <p>Applies only to TextViews.
     */
    public static class TextLinkSpan extends ClickableSpan {

        /**
         * How the clickspan is triggered.
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({INVOCATION_METHOD_UNSPECIFIED, INVOCATION_METHOD_TOUCH,
                INVOCATION_METHOD_KEYBOARD})
        public @interface InvocationMethod {}

        /** @hide */
        public static final int INVOCATION_METHOD_UNSPECIFIED = -1;
        /** @hide */
        public static final int INVOCATION_METHOD_TOUCH = 0;
        /** @hide */
        public static final int INVOCATION_METHOD_KEYBOARD = 1;

        private final TextLink mTextLink;

        public TextLinkSpan(@NonNull TextLink textLink) {
            mTextLink = textLink;
        }

        @Override
        public void onClick(View widget) {
            onClick(widget, INVOCATION_METHOD_UNSPECIFIED);
        }

        /** @hide */
        public final void onClick(View widget, @InvocationMethod int invocationMethod) {
            if (widget instanceof TextView) {
                final TextView textView = (TextView) widget;
                final Context context = textView.getContext();
                if (TextClassificationManager.getSettings(context).isSmartLinkifyEnabled()) {
                    switch (invocationMethod) {
                        case INVOCATION_METHOD_TOUCH:
                            textView.requestActionMode(this);
                            break;
                        case INVOCATION_METHOD_KEYBOARD:// fall though
                        case INVOCATION_METHOD_UNSPECIFIED:  // fall through
                        default:
                            textView.handleClick(this);
                            break;
                    }
                } else {
                    if (mTextLink.mUrlSpan != null) {
                        mTextLink.mUrlSpan.onClick(textView);
                    } else {
                        textView.handleClick(this);
                    }
                }
            }
        }

        public final TextLink getTextLink() {
            return mTextLink;
        }

        /** @hide */
        @VisibleForTesting(visibility = Visibility.PRIVATE)
        @Nullable
        public final String getUrl() {
            if (mTextLink.mUrlSpan != null) {
                return mTextLink.mUrlSpan.getURL();
            }
            return null;
        }
    }

    /**
     * A builder to construct a TextLinks instance.
     */
    public static final class Builder {
        private final String mFullText;
        private final ArrayList<TextLink> mLinks;
        private Bundle mExtras;

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
         * @param start The start index of the identified subsequence
         * @param end The end index of the identified subsequence
         * @param entityScores A mapping of entity type to confidence score
         *
         * @throws IllegalArgumentException if entityScores is null or empty.
         */
        @NonNull
        public Builder addLink(int start, int end, @NonNull Map<String, Float> entityScores) {
            return addLink(start, end, entityScores, Bundle.EMPTY, null);
        }

        /**
         * Adds a TextLink.
         *
         * @see #addLink(int, int, Map)
         * @param extras An optional bundle containing custom data related to this TextLink
         */
        @NonNull
        public Builder addLink(int start, int end, @NonNull Map<String, Float> entityScores,
                @NonNull Bundle extras) {
            return addLink(start, end, entityScores, extras, null);
        }

        /**
         * @see #addLink(int, int, Map)
         * @param urlSpan An optional URLSpan to delegate to. NOTE: Not parcelled.
         */
        @NonNull
        Builder addLink(int start, int end, @NonNull Map<String, Float> entityScores,
                @Nullable URLSpan urlSpan) {
            return addLink(start, end, entityScores, Bundle.EMPTY, urlSpan);
        }

        private Builder addLink(int start, int end, @NonNull Map<String, Float> entityScores,
                @NonNull Bundle extras, @Nullable URLSpan urlSpan) {
            mLinks.add(new TextLink(
                    start, end, new EntityConfidence(entityScores), extras, urlSpan));
            return this;
        }

        /**
         * Removes all {@link TextLink}s.
         */
        @NonNull
        public Builder clearTextLinks() {
            mLinks.clear();
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
         * Constructs a TextLinks instance.
         *
         * @return the constructed TextLinks
         */
        @NonNull
        public TextLinks build() {
            return new TextLinks(mFullText, mLinks,
                    mExtras == null ? Bundle.EMPTY : mExtras);
        }
    }
}
