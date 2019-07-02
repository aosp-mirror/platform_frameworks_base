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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.WorkerThread;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.text.util.Linkify.LinkifyMask;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for providing text classification related features.
 * <p>
 * The TextClassifier may be used to understand the meaning of text, as well as generating predicted
 * next actions based on the text.
 *
 * <p><strong>NOTE: </strong>Unless otherwise stated, methods of this interface are blocking
 * operations. Call on a worker thread.
 */
public interface TextClassifier {

    /** @hide */
    String DEFAULT_LOG_TAG = "androidtc";


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {LOCAL, SYSTEM})
    @interface TextClassifierType {}  // TODO: Expose as system APIs.
    /** Specifies a TextClassifier that runs locally in the app's process. @hide */
    int LOCAL = 0;
    /** Specifies a TextClassifier that runs in the system process and serves all apps. @hide */
    int SYSTEM = 1;

    /** The TextClassifier failed to run. */
    String TYPE_UNKNOWN = "";
    /** The classifier ran, but didn't recognize a known entity. */
    String TYPE_OTHER = "other";
    /** E-mail address (e.g. "noreply@android.com"). */
    String TYPE_EMAIL = "email";
    /** Phone number (e.g. "555-123 456"). */
    String TYPE_PHONE = "phone";
    /** Physical address. */
    String TYPE_ADDRESS = "address";
    /** Web URL. */
    String TYPE_URL = "url";
    /** Time reference that is no more specific than a date. May be absolute such as "01/01/2000" or
     * relative like "tomorrow". **/
    String TYPE_DATE = "date";
    /** Time reference that includes a specific time. May be absolute such as "01/01/2000 5:30pm" or
     * relative like "tomorrow at 5:30pm". **/
    String TYPE_DATE_TIME = "datetime";
    /** Flight number in IATA format. */
    String TYPE_FLIGHT_NUMBER = "flight";
    /**
     * Word that users may be interested to look up for meaning.
     * @hide
     */
    String TYPE_DICTIONARY = "dictionary";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = { "TYPE_" }, value = {
            TYPE_UNKNOWN,
            TYPE_OTHER,
            TYPE_EMAIL,
            TYPE_PHONE,
            TYPE_ADDRESS,
            TYPE_URL,
            TYPE_DATE,
            TYPE_DATE_TIME,
            TYPE_FLIGHT_NUMBER,
            TYPE_DICTIONARY
    })
    @interface EntityType {}

    /** Designates that the text in question is editable. **/
    String HINT_TEXT_IS_EDITABLE = "android.text_is_editable";
    /** Designates that the text in question is not editable. **/
    String HINT_TEXT_IS_NOT_EDITABLE = "android.text_is_not_editable";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = { "HINT_" }, value = {HINT_TEXT_IS_EDITABLE, HINT_TEXT_IS_NOT_EDITABLE})
    @interface Hints {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({WIDGET_TYPE_TEXTVIEW, WIDGET_TYPE_EDITTEXT, WIDGET_TYPE_UNSELECTABLE_TEXTVIEW,
            WIDGET_TYPE_WEBVIEW, WIDGET_TYPE_EDIT_WEBVIEW, WIDGET_TYPE_CUSTOM_TEXTVIEW,
            WIDGET_TYPE_CUSTOM_EDITTEXT, WIDGET_TYPE_CUSTOM_UNSELECTABLE_TEXTVIEW,
            WIDGET_TYPE_NOTIFICATION, WIDGET_TYPE_UNKNOWN})
    @interface WidgetType {}

    /** The widget involved in the text classification context is a standard
     * {@link android.widget.TextView}. */
    String WIDGET_TYPE_TEXTVIEW = "textview";
    /** The widget involved in the text classification context is a standard
     * {@link android.widget.EditText}. */
    String WIDGET_TYPE_EDITTEXT = "edittext";
    /** The widget involved in the text classification context is a standard non-selectable
     * {@link android.widget.TextView}. */
    String WIDGET_TYPE_UNSELECTABLE_TEXTVIEW = "nosel-textview";
    /** The widget involved in the text classification context is a standard
     * {@link android.webkit.WebView}. */
    String WIDGET_TYPE_WEBVIEW = "webview";
    /** The widget involved in the text classification context is a standard editable
     * {@link android.webkit.WebView}. */
    String WIDGET_TYPE_EDIT_WEBVIEW = "edit-webview";
    /** The widget involved in the text classification context is a custom text widget. */
    String WIDGET_TYPE_CUSTOM_TEXTVIEW = "customview";
    /** The widget involved in the text classification context is a custom editable text widget. */
    String WIDGET_TYPE_CUSTOM_EDITTEXT = "customedit";
    /** The widget involved in the text classification context is a custom non-selectable text
     * widget. */
    String WIDGET_TYPE_CUSTOM_UNSELECTABLE_TEXTVIEW = "nosel-customview";
    /** The widget involved in the text classification context is a notification */
    String WIDGET_TYPE_NOTIFICATION = "notification";
    /** The widget involved in the text classification context is of an unknown/unspecified type. */
    String WIDGET_TYPE_UNKNOWN = "unknown";

    /**
     * No-op TextClassifier.
     * This may be used to turn off TextClassifier features.
     */
    TextClassifier NO_OP = new TextClassifier() {
        @Override
        public String toString() {
            return "TextClassifier.NO_OP";
        }
    };

    /**
     * Extra that is included on activity intents coming from a TextClassifier when
     * it suggests actions to its caller.
     * <p>
     * All {@link TextClassifier} implementations should make sure this extra exists in their
     * generated intents.
     */
    String EXTRA_FROM_TEXT_CLASSIFIER = "android.view.textclassifier.extra.FROM_TEXT_CLASSIFIER";

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @param request the text selection request
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(@NonNull TextSelection.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        return new TextSelection.Builder(request.getStartIndex(), request.getEndIndex()).build();
    }

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #suggestSelection(TextSelection.Request)}. If that method calls this method,
     * a stack overflow error will happen.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     * @param defaultLocales ordered list of locale preferences that may be used to
     *      disambiguate the provided text. If no locale preferences exist, set this to null
     *      or an empty locale list.
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or not greater than selectionStartIndex
     *
     * @see #suggestSelection(TextSelection.Request)
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable LocaleList defaultLocales) {
        final TextSelection.Request request = new TextSelection.Request.Builder(
                text, selectionStartIndex, selectionEndIndex)
                .setDefaultLocales(defaultLocales)
                .build();
        return suggestSelection(request);
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @param request the text classification request
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(@NonNull TextClassification.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        return TextClassification.EMPTY;
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #classifyText(TextClassification.Request)}. If that method calls this method,
     * a stack overflow error will happen.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     * @param defaultLocales ordered list of locale preferences that may be used to
     *      disambiguate the provided text. If no locale preferences exist, set this to null
     *      or an empty locale list.
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or not greater than startIndex
     *
     * @see #classifyText(TextClassification.Request)
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable LocaleList defaultLocales) {
        final TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(defaultLocales)
                .build();
        return classifyText(request);
    }

    /**
     * Generates and returns a {@link TextLinks} that may be applied to the text to annotate it with
     * links information.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @param request the text links request
     *
     * @see #getMaxGenerateLinksTextLength()
     */
    @WorkerThread
    @NonNull
    default TextLinks generateLinks(@NonNull TextLinks.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        return new TextLinks.Builder(request.getText().toString()).build();
    }

    /**
     * Returns the maximal length of text that can be processed by generateLinks.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @see #generateLinks(TextLinks.Request)
     */
    @WorkerThread
    default int getMaxGenerateLinksTextLength() {
        return Integer.MAX_VALUE;
    }

    /**
     * Detects the language of the text in the given request.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @param request the {@link TextLanguage} request.
     * @return the {@link TextLanguage} result.
     */
    @WorkerThread
    @NonNull
    default TextLanguage detectLanguage(@NonNull TextLanguage.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        return TextLanguage.EMPTY;
    }

    /**
     * Suggests and returns a list of actions according to the given conversation.
     */
    @WorkerThread
    @NonNull
    default ConversationActions suggestConversationActions(
            @NonNull ConversationActions.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        return new ConversationActions(Collections.emptyList(), null);
    }

    /**
     * <strong>NOTE: </strong>Use {@link #onTextClassifierEvent(TextClassifierEvent)} instead.
     * <p>
     * Reports a selection event.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     */
    default void onSelectionEvent(@NonNull SelectionEvent event) {
        // TODO: Consider rerouting to onTextClassifierEvent()
    }

    /**
     * Reports a text classifier event.
     * <p>
     * <strong>NOTE: </strong>Call on a worker thread.
     *
     * @throws IllegalStateException if this TextClassifier has been destroyed.
     * @see #isDestroyed()
     */
    default void onTextClassifierEvent(@NonNull TextClassifierEvent event) {}

    /**
     * Destroys this TextClassifier.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to its methods should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * <p>Subsequent calls to this method are no-ops.
     */
    default void destroy() {}

    /**
     * Returns whether or not this TextClassifier has been destroyed.
     *
     * <p><strong>NOTE: </strong>If a TextClassifier has been destroyed, caller should not interact
     * with the classifier and an attempt to do so would throw an {@link IllegalStateException}.
     * However, this method should never throw an {@link IllegalStateException}.
     *
     * @see #destroy()
     */
    default boolean isDestroyed() {
        return false;
    }

    /** @hide **/
    default void dump(@NonNull IndentingPrintWriter printWriter) {}

    /**
     * Configuration object for specifying what entity types to identify.
     *
     * Configs are initially based on a predefined preset, and can be modified from there.
     */
    final class EntityConfig implements Parcelable {
        private final List<String> mIncludedTypes;
        private final List<String> mExcludedTypes;
        private final List<String> mHints;
        private final boolean mIncludeTypesFromTextClassifier;

        private EntityConfig(
                List<String> includedEntityTypes,
                List<String> excludedEntityTypes,
                List<String> hints,
                boolean includeTypesFromTextClassifier) {
            mIncludedTypes = Preconditions.checkNotNull(includedEntityTypes);
            mExcludedTypes = Preconditions.checkNotNull(excludedEntityTypes);
            mHints = Preconditions.checkNotNull(hints);
            mIncludeTypesFromTextClassifier = includeTypesFromTextClassifier;
        }

        private EntityConfig(Parcel in) {
            mIncludedTypes = new ArrayList<>();
            in.readStringList(mIncludedTypes);
            mExcludedTypes = new ArrayList<>();
            in.readStringList(mExcludedTypes);
            List<String> tmpHints = new ArrayList<>();
            in.readStringList(tmpHints);
            mHints = Collections.unmodifiableList(tmpHints);
            mIncludeTypesFromTextClassifier = in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeStringList(mIncludedTypes);
            parcel.writeStringList(mExcludedTypes);
            parcel.writeStringList(mHints);
            parcel.writeByte((byte) (mIncludeTypesFromTextClassifier ? 1 : 0));
        }

        /**
         * Creates an EntityConfig.
         *
         * @param hints Hints for the TextClassifier to determine what types of entities to find.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public static EntityConfig createWithHints(@Nullable Collection<String> hints) {
            return new EntityConfig.Builder()
                    .includeTypesFromTextClassifier(true)
                    .setHints(hints)
                    .build();
        }

        /**
         * Creates an EntityConfig.
         *
         * @param hints Hints for the TextClassifier to determine what types of entities to find
         * @param includedEntityTypes Entity types, e.g. {@link #TYPE_EMAIL}, to explicitly include
         * @param excludedEntityTypes Entity types, e.g. {@link #TYPE_PHONE}, to explicitly exclude
         *
         *
         * Note that if an entity has been excluded, the exclusion will take precedence.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public static EntityConfig create(@Nullable Collection<String> hints,
                @Nullable Collection<String> includedEntityTypes,
                @Nullable Collection<String> excludedEntityTypes) {
            return new EntityConfig.Builder()
                    .setIncludedTypes(includedEntityTypes)
                    .setExcludedTypes(excludedEntityTypes)
                    .setHints(hints)
                    .includeTypesFromTextClassifier(true)
                    .build();
        }

        /**
         * Creates an EntityConfig with an explicit entity list.
         *
         * @param entityTypes Complete set of entities, e.g. {@link #TYPE_URL} to find.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public static EntityConfig createWithExplicitEntityList(
                @Nullable Collection<String> entityTypes) {
            return new EntityConfig.Builder()
                    .setIncludedTypes(entityTypes)
                    .includeTypesFromTextClassifier(false)
                    .build();
        }

        /**
         * Returns a final list of entity types to find.
         *
         * @param entityTypes Entity types we think should be found before factoring in
         *                    includes/excludes
         *
         * This method is intended for use by TextClassifier implementations.
         */
        public Collection<String> resolveEntityListModifications(
                @NonNull Collection<String> entityTypes) {
            final Set<String> finalSet = new HashSet<>();
            if (mIncludeTypesFromTextClassifier) {
                finalSet.addAll(entityTypes);
            }
            finalSet.addAll(mIncludedTypes);
            finalSet.removeAll(mExcludedTypes);
            return finalSet;
        }

        /**
         * Retrieves the list of hints.
         *
         * @return An unmodifiable collection of the hints.
         */
        public Collection<String> getHints() {
            return mHints;
        }

        /**
         * Return whether the client allows the text classifier to include its own list of
         * default types. If this function returns {@code true}, a default list of types suggested
         * from a text classifier will be taking into account.
         *
         * <p>NOTE: This method is intended for use by a text classifier.
         *
         * @see #resolveEntityListModifications(Collection)
         */
        public boolean shouldIncludeTypesFromTextClassifier() {
            return mIncludeTypesFromTextClassifier;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<EntityConfig> CREATOR =
                new Parcelable.Creator<EntityConfig>() {
                    @Override
                    public EntityConfig createFromParcel(Parcel in) {
                        return new EntityConfig(in);
                    }

                    @Override
                    public EntityConfig[] newArray(int size) {
                        return new EntityConfig[size];
                    }
                };



        /** Builder class to construct the {@link EntityConfig} object. */
        public static final class Builder {
            @Nullable
            private Collection<String> mIncludedTypes;
            @Nullable
            private Collection<String> mExcludedTypes;
            @Nullable
            private Collection<String> mHints;
            private boolean mIncludeTypesFromTextClassifier = true;

            /**
             * Sets a collection of types that are explicitly included.
             */
            @NonNull
            public Builder setIncludedTypes(@Nullable Collection<String> includedTypes) {
                mIncludedTypes = includedTypes;
                return this;
            }

            /**
             * Sets a collection of types that are explicitly excluded.
             */
            @NonNull
            public Builder setExcludedTypes(@Nullable Collection<String> excludedTypes) {
                mExcludedTypes = excludedTypes;
                return this;
            }

            /**
             * Specifies whether or not to include the types suggested by the text classifier. By
             * default, it is included.
             */
            @NonNull
            public Builder includeTypesFromTextClassifier(boolean includeTypesFromTextClassifier) {
                mIncludeTypesFromTextClassifier = includeTypesFromTextClassifier;
                return this;
            }


            /**
             * Sets the hints for the TextClassifier to determine what types of entities to find.
             * These hints will only be used if {@link #includeTypesFromTextClassifier} is
             * set to be true.
             */
            @NonNull
            public Builder setHints(@Nullable Collection<String> hints) {
                mHints = hints;
                return this;
            }

            /**
             * Combines all of the options that have been set and returns a new {@link EntityConfig}
             * object.
             */
            @NonNull
            public EntityConfig build() {
                return new EntityConfig(
                        mIncludedTypes == null
                                ? Collections.emptyList()
                                : new ArrayList<>(mIncludedTypes),
                        mExcludedTypes == null
                                ? Collections.emptyList()
                                : new ArrayList<>(mExcludedTypes),
                        mHints == null
                                ? Collections.emptyList()
                                : Collections.unmodifiableList(new ArrayList<>(mHints)),
                        mIncludeTypesFromTextClassifier);
            }
        }
    }

    /**
     * Utility functions for TextClassifier methods.
     *
     * <ul>
     *  <li>Provides validation of input parameters to TextClassifier methods
     * </ul>
     *
     * Intended to be used only for TextClassifier purposes.
     * @hide
     */
    final class Utils {

        @GuardedBy("WORD_ITERATOR")
        private static final BreakIterator WORD_ITERATOR = BreakIterator.getWordInstance();

        /**
         * @throws IllegalArgumentException if text is null; startIndex is negative;
         *      endIndex is greater than text.length() or is not greater than startIndex;
         *      options is null
         */
        static void checkArgument(@NonNull CharSequence text, int startIndex, int endIndex) {
            Preconditions.checkArgument(text != null);
            Preconditions.checkArgument(startIndex >= 0);
            Preconditions.checkArgument(endIndex <= text.length());
            Preconditions.checkArgument(endIndex > startIndex);
        }

        static void checkTextLength(CharSequence text, int maxLength) {
            Preconditions.checkArgumentInRange(text.length(), 0, maxLength, "text.length()");
        }

        /**
         * Returns the substring of {@code text} that contains at least text from index
         * {@code start} <i>(inclusive)</i> to index {@code end} <i><(exclusive)/i> with the goal of
         * returning text that is at least {@code minimumLength}. If {@code text} is not long
         * enough, this will return {@code text}. This method returns text at word boundaries.
         *
         * @param text the source text
         * @param start the start index of text that must be included
         * @param end the end index of text that must be included
         * @param minimumLength minimum length of text to return if {@code text} is long enough
         */
        public static String getSubString(
                String text, int start, int end, int minimumLength) {
            Preconditions.checkArgument(start >= 0);
            Preconditions.checkArgument(end <= text.length());
            Preconditions.checkArgument(start <= end);

            if (text.length() < minimumLength) {
                return text;
            }

            final int length = end - start;
            if (length >= minimumLength) {
                return text.substring(start, end);
            }

            final int offset = (minimumLength - length) / 2;
            int iterStart = Math.max(0, Math.min(start - offset, text.length() - minimumLength));
            int iterEnd = Math.min(text.length(), iterStart + minimumLength);

            synchronized (WORD_ITERATOR) {
                WORD_ITERATOR.setText(text);
                iterStart = WORD_ITERATOR.isBoundary(iterStart)
                        ? iterStart : Math.max(0, WORD_ITERATOR.preceding(iterStart));
                iterEnd = WORD_ITERATOR.isBoundary(iterEnd)
                        ? iterEnd : Math.max(iterEnd, WORD_ITERATOR.following(iterEnd));
                WORD_ITERATOR.setText("");
                return text.substring(iterStart, iterEnd);
            }
        }

        /**
         * Generates links using legacy {@link Linkify}.
         */
        public static TextLinks generateLegacyLinks(@NonNull TextLinks.Request request) {
            final String string = request.getText().toString();
            final TextLinks.Builder links = new TextLinks.Builder(string);

            final Collection<String> entities = request.getEntityConfig()
                    .resolveEntityListModifications(Collections.emptyList());
            if (entities.contains(TextClassifier.TYPE_URL)) {
                addLinks(links, string, TextClassifier.TYPE_URL);
            }
            if (entities.contains(TextClassifier.TYPE_PHONE)) {
                addLinks(links, string, TextClassifier.TYPE_PHONE);
            }
            if (entities.contains(TextClassifier.TYPE_EMAIL)) {
                addLinks(links, string, TextClassifier.TYPE_EMAIL);
            }
            // NOTE: Do not support MAP_ADDRESSES. Legacy version does not work well.
            return links.build();
        }

        private static void addLinks(
                TextLinks.Builder links, String string, @EntityType String entityType) {
            final Spannable spannable = new SpannableString(string);
            if (Linkify.addLinks(spannable, linkMask(entityType))) {
                final URLSpan[] spans = spannable.getSpans(0, spannable.length(), URLSpan.class);
                for (URLSpan urlSpan : spans) {
                    links.addLink(
                            spannable.getSpanStart(urlSpan),
                            spannable.getSpanEnd(urlSpan),
                            entityScores(entityType),
                            urlSpan);
                }
            }
        }

        @LinkifyMask
        private static int linkMask(@EntityType String entityType) {
            switch (entityType) {
                case TextClassifier.TYPE_URL:
                    return Linkify.WEB_URLS;
                case TextClassifier.TYPE_PHONE:
                    return Linkify.PHONE_NUMBERS;
                case TextClassifier.TYPE_EMAIL:
                    return Linkify.EMAIL_ADDRESSES;
                default:
                    // NOTE: Do not support MAP_ADDRESSES. Legacy version does not work well.
                    return 0;
            }
        }

        private static Map<String, Float> entityScores(@EntityType String entityType) {
            final Map<String, Float> scores = new ArrayMap<>();
            scores.put(entityType, 1f);
            return scores;
        }

        static void checkMainThread() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.w(DEFAULT_LOG_TAG, "TextClassifier called on main thread");
            }
        }
    }
}
