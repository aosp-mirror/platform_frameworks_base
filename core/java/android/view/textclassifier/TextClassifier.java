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
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Interface for providing text classification related features.
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
    @StringDef({WIDGET_TYPE_TEXTVIEW, WIDGET_TYPE_WEBVIEW, WIDGET_TYPE_EDITTEXT,
            WIDGET_TYPE_EDIT_WEBVIEW, WIDGET_TYPE_CUSTOM_TEXTVIEW, WIDGET_TYPE_CUSTOM_EDITTEXT,
            WIDGET_TYPE_CUSTOM_UNSELECTABLE_TEXTVIEW, WIDGET_TYPE_UNKNOWN})
    @interface WidgetType {}

    /** The widget involved in the text classification session is a standard
     * {@link android.widget.TextView}. */
    String WIDGET_TYPE_TEXTVIEW = "textview";
    /** The widget involved in the text classification session is a standard
     * {@link android.widget.EditText}. */
    String WIDGET_TYPE_EDITTEXT = "edittext";
    /** The widget involved in the text classification session is a standard non-selectable
     * {@link android.widget.TextView}. */
    String WIDGET_TYPE_UNSELECTABLE_TEXTVIEW = "nosel-textview";
    /** The widget involved in the text classification session is a standard
     * {@link android.webkit.WebView}. */
    String WIDGET_TYPE_WEBVIEW = "webview";
    /** The widget involved in the text classification session is a standard editable
     * {@link android.webkit.WebView}. */
    String WIDGET_TYPE_EDIT_WEBVIEW = "edit-webview";
    /** The widget involved in the text classification session is a custom text widget. */
    String WIDGET_TYPE_CUSTOM_TEXTVIEW = "customview";
    /** The widget involved in the text classification session is a custom editable text widget. */
    String WIDGET_TYPE_CUSTOM_EDITTEXT = "customedit";
    /** The widget involved in the text classification session is a custom non-selectable text
     * widget. */
    String WIDGET_TYPE_CUSTOM_UNSELECTABLE_TEXTVIEW = "nosel-customview";
    /** The widget involved in the text classification session is of an unknown/unspecified type. */
    String WIDGET_TYPE_UNKNOWN = "unknown";

    /**
     * No-op TextClassifier.
     * This may be used to turn off TextClassifier features.
     */
    TextClassifier NO_OP = new TextClassifier() {};

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

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        final TextSelection.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextSelection.Request.Builder(
                        text, selectionStartIndex, selectionEndIndex)
                        .setDefaultLocales(options.getDefaultLocales())
                        .build();
        return suggestSelection(request);
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
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
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
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

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options) {
        final TextClassification.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextClassification.Request.Builder(
                        text, startIndex, endIndex)
                        .setDefaultLocales(options.getDefaultLocales())
                        .setReferenceTime(options.getReferenceTime())
                        .build();
        return classifyText(request);
    }

    /**
     * Generates and returns a {@link TextLinks} that may be applied to the text to annotate it with
     * links information.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
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

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    default TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        final TextLinks.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextLinks.Request.Builder(text)
                        .setDefaultLocales(options.getDefaultLocales())
                        .setEntityConfig(options.getEntityConfig())
                        .build();
        return generateLinks(request);
    }

    /**
     * Returns the maximal length of text that can be processed by generateLinks.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * @see #generateLinks(TextLinks.Request)
     */
    @WorkerThread
    default int getMaxGenerateLinksTextLength() {
        return Integer.MAX_VALUE;
    }

    /**
     * Reports a selection event.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to this method should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     */
    default void onSelectionEvent(@NonNull SelectionEvent event) {}

    /**
     * Destroys this TextClassifier.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, calls to its methods should
     * throw an {@link IllegalStateException}. See {@link #isDestroyed()}.
     *
     * <p>Subsequent calls to this method are no-ops.
     */
    default void destroy() {}

    /**
     * Returns whether or not this TextClassifier has been destroyed.
     *
     * <strong>NOTE: </strong>If a TextClassifier has been destroyed, caller should not interact
     * with the classifier and an attempt to do so would throw an {@link IllegalStateException}.
     * However, this method should never throw an {@link IllegalStateException}.
     *
     * @see #destroy()
     */
    default boolean isDestroyed() {
        return false;
    }

    /**
     * Configuration object for specifying what entities to identify.
     *
     * Configs are initially based on a predefined preset, and can be modified from there.
     */
    final class EntityConfig implements Parcelable {
        private final Collection<String> mHints;
        private final Collection<String> mExcludedEntityTypes;
        private final Collection<String> mIncludedEntityTypes;
        private final boolean mUseHints;

        private EntityConfig(boolean useHints, Collection<String> hints,
                Collection<String> includedEntityTypes, Collection<String> excludedEntityTypes) {
            mHints = hints == null
                    ? Collections.EMPTY_LIST
                    : Collections.unmodifiableCollection(new ArraySet<>(hints));
            mExcludedEntityTypes = excludedEntityTypes == null
                    ? Collections.EMPTY_LIST : new ArraySet<>(excludedEntityTypes);
            mIncludedEntityTypes = includedEntityTypes == null
                    ? Collections.EMPTY_LIST : new ArraySet<>(includedEntityTypes);
            mUseHints = useHints;
        }

        /**
         * Creates an EntityConfig.
         *
         * @param hints Hints for the TextClassifier to determine what types of entities to find.
         */
        public static EntityConfig createWithHints(@Nullable Collection<String> hints) {
            return new EntityConfig(/* useHints */ true, hints,
                    /* includedEntityTypes */null, /* excludedEntityTypes */ null);
        }

        // TODO: Remove once apps can build against the latest sdk.
        /** @hide */
        public static EntityConfig create(@Nullable Collection<String> hints) {
            return createWithHints(hints);
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
         */
        public static EntityConfig create(@Nullable Collection<String> hints,
                @Nullable Collection<String> includedEntityTypes,
                @Nullable Collection<String> excludedEntityTypes) {
            return new EntityConfig(/* useHints */ true, hints,
                    includedEntityTypes, excludedEntityTypes);
        }

        /**
         * Creates an EntityConfig with an explicit entity list.
         *
         * @param entityTypes Complete set of entities, e.g. {@link #TYPE_URL} to find.
         *
         */
        public static EntityConfig createWithExplicitEntityList(
                @Nullable Collection<String> entityTypes) {
            return new EntityConfig(/* useHints */ false, /* hints */ null,
                    /* includedEntityTypes */ entityTypes, /* excludedEntityTypes */ null);
        }

        // TODO: Remove once apps can build against the latest sdk.
        /** @hide */
        public static EntityConfig createWithEntityList(@Nullable Collection<String> entityTypes) {
            return createWithExplicitEntityList(entityTypes);
        }

        /**
         * Returns a list of the final set of entities to find.
         *
         * @param entities Entities we think should be found before factoring in includes/excludes
         *
         * This method is intended for use by TextClassifier implementations.
         */
        public Collection<String> resolveEntityListModifications(
                @NonNull Collection<String> entities) {
            final Set<String> finalSet = new HashSet();
            if (mUseHints) {
                finalSet.addAll(entities);
            }
            finalSet.addAll(mIncludedEntityTypes);
            finalSet.removeAll(mExcludedEntityTypes);
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

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStringList(new ArrayList<>(mHints));
            dest.writeStringList(new ArrayList<>(mExcludedEntityTypes));
            dest.writeStringList(new ArrayList<>(mIncludedEntityTypes));
            dest.writeInt(mUseHints ? 1 : 0);
        }

        public static final Parcelable.Creator<EntityConfig> CREATOR =
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

        private EntityConfig(Parcel in) {
            mHints = new ArraySet<>(in.createStringArrayList());
            mExcludedEntityTypes = new ArraySet<>(in.createStringArrayList());
            mIncludedEntityTypes = new ArraySet<>(in.createStringArrayList());
            mUseHints = in.readInt() == 1;
        }
    }

    /**
     * Utility functions for TextClassifier methods.
     *
     * <ul>
     *  <li>Provides validation of input parameters to TextClassifier methods
     * </ul>
     *
     * Intended to be used only in this package.
     * @hide
     */
    final class Utils {

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
