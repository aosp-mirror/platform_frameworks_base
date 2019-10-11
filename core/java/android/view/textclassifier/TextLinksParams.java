/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.Spannable;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.text.util.Linkify.LinkifyMask;
import android.view.textclassifier.TextLinks.TextLink;
import android.view.textclassifier.TextLinks.TextLinkSpan;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Parameters for generating and applying links.
 * @hide
 */
public final class TextLinksParams {

    /**
     * A function to create spans from TextLinks.
     */
    private static final Function<TextLink, TextLinkSpan> DEFAULT_SPAN_FACTORY =
            textLink -> new TextLinkSpan(textLink);

    @TextLinks.ApplyStrategy
    private final int mApplyStrategy;
    private final Function<TextLink, TextLinkSpan> mSpanFactory;
    private final TextClassifier.EntityConfig mEntityConfig;

    private TextLinksParams(
            @TextLinks.ApplyStrategy int applyStrategy,
            Function<TextLink, TextLinkSpan> spanFactory) {
        mApplyStrategy = applyStrategy;
        mSpanFactory = spanFactory;
        mEntityConfig = TextClassifier.EntityConfig.createWithHints(null);
    }

    /**
     * Returns a new TextLinksParams object based on the specified link mask.
     *
     * @param mask the link mask
     *      e.g. {@link LinkifyMask#PHONE_NUMBERS} | {@link LinkifyMask#EMAIL_ADDRESSES}
     * @hide
     */
    @NonNull
    public static TextLinksParams fromLinkMask(@LinkifyMask int mask) {
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
        return new TextLinksParams.Builder().setEntityConfig(
                TextClassifier.EntityConfig.createWithExplicitEntityList(entitiesToFind))
                .build();
    }

    /**
     * Returns the entity config used to determine what entity types to generate.
     */
    @NonNull
    public TextClassifier.EntityConfig getEntityConfig() {
        return mEntityConfig;
    }

    /**
     * Annotates the given text with the generated links. It will fail if the provided text doesn't
     * match the original text used to crete the TextLinks.
     *
     * @param text the text to apply the links to. Must match the original text
     * @param textLinks the links to apply to the text
     *
     * @return a status code indicating whether or not the links were successfully applied
     * @hide
     */
    @TextLinks.Status
    public int apply(@NonNull Spannable text, @NonNull TextLinks textLinks) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(textLinks);

        final String textString = text.toString();

        if (Linkify.containsUnsupportedCharacters(textString)) {
            // Do not apply links to text containing unsupported characters.
            android.util.EventLog.writeEvent(0x534e4554, "116321860", -1, "");
            return TextLinks.STATUS_UNSUPPORTED_CHARACTER;
        }

        if (!textString.startsWith(textLinks.getText())) {
            return TextLinks.STATUS_DIFFERENT_TEXT;
        }
        if (textLinks.getLinks().isEmpty()) {
            return TextLinks.STATUS_NO_LINKS_FOUND;
        }

        int applyCount = 0;
        for (TextLink link : textLinks.getLinks()) {
            final TextLinkSpan span = mSpanFactory.apply(link);
            if (span != null) {
                final ClickableSpan[] existingSpans = text.getSpans(
                        link.getStart(), link.getEnd(), ClickableSpan.class);
                if (existingSpans.length > 0) {
                    if (mApplyStrategy == TextLinks.APPLY_STRATEGY_REPLACE) {
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
            return TextLinks.STATUS_NO_LINKS_APPLIED;
        }
        return TextLinks.STATUS_LINKS_APPLIED;
    }

    /**
     * A builder for building TextLinksParams.
     */
    public static final class Builder {

        @TextLinks.ApplyStrategy
        private int mApplyStrategy = TextLinks.APPLY_STRATEGY_IGNORE;
        private Function<TextLink, TextLinkSpan> mSpanFactory = DEFAULT_SPAN_FACTORY;

        /**
         * Sets the apply strategy used to determine how to apply links to text.
         *      e.g {@link TextLinks#APPLY_STRATEGY_IGNORE}
         *
         * @return this builder
         */
        public Builder setApplyStrategy(@TextLinks.ApplyStrategy int applyStrategy) {
            mApplyStrategy = checkApplyStrategy(applyStrategy);
            return this;
        }

        /**
         * Sets a custom span factory for converting TextLinks to TextLinkSpans.
         * Set to {@code null} to use the default span factory.
         *
         * @return this builder
         */
        public Builder setSpanFactory(@Nullable Function<TextLink, TextLinkSpan> spanFactory) {
            mSpanFactory = spanFactory == null ? DEFAULT_SPAN_FACTORY : spanFactory;
            return this;
        }

        /**
         * Sets the entity configuration used to determine what entity types to generate.
         * Set to {@code null} for the default entity config which will automatically determine
         * what links to generate.
         *
         * @return this builder
         */
        public Builder setEntityConfig(@Nullable TextClassifier.EntityConfig entityConfig) {
            return this;
        }

        /**
         * Builds and returns a TextLinksParams object.
         */
        public TextLinksParams build() {
            return new TextLinksParams(mApplyStrategy, mSpanFactory);
        }
    }

    /** @throws IllegalArgumentException if the value is invalid */
    @TextLinks.ApplyStrategy
    private static int checkApplyStrategy(int applyStrategy) {
        if (applyStrategy != TextLinks.APPLY_STRATEGY_IGNORE
                && applyStrategy != TextLinks.APPLY_STRATEGY_REPLACE) {
            throw new IllegalArgumentException(
                    "Invalid apply strategy. See TextLinksParams.ApplyStrategy for options.");
        }
        return applyStrategy;
    }
}

