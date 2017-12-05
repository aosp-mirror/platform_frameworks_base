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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.WorkerThread;
import android.os.LocaleList;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for providing text classification related features.
 *
 * <p>Unless otherwise stated, methods of this interface are blocking operations.
 * Avoid calling them on the UI thread.
 */
public interface TextClassifier {

    /** @hide */
    String DEFAULT_LOG_TAG = "androidtc";

    String TYPE_UNKNOWN = "";
    String TYPE_OTHER = "other";
    String TYPE_EMAIL = "email";
    String TYPE_PHONE = "phone";
    String TYPE_ADDRESS = "address";
    String TYPE_URL = "url";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            TYPE_UNKNOWN, TYPE_OTHER, TYPE_EMAIL, TYPE_PHONE, TYPE_ADDRESS, TYPE_URL
    })
    @interface EntityType {}

    /**
     * No-op TextClassifier.
     * This may be used to turn off TextClassifier features.
     */
    TextClassifier NO_OP = new TextClassifier() {};

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     * @param options optional input parameters
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or not greater than selectionStartIndex
     *
     * @see #suggestSelection(CharSequence, int, int)
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        Utils.validateInput(text, selectionStartIndex, selectionEndIndex);
        return new TextSelection.Builder(selectionStartIndex, selectionEndIndex).build();
    }

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or not greater than selectionStartIndex
     *
     * @see #suggestSelection(CharSequence, int, int, TextSelection.Options)
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex) {
        return suggestSelection(text, selectionStartIndex, selectionEndIndex,
                (TextSelection.Options) null);
    }

    /**
     * See {@link #suggestSelection(CharSequence, int, int)} or
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #suggestSelection(CharSequence, int, int, TextSelection.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     */
    @WorkerThread
    @NonNull
    default TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable LocaleList defaultLocales) {
        final TextSelection.Options options = (defaultLocales != null)
                ? new TextSelection.Options().setDefaultLocales(defaultLocales)
                : null;
        return suggestSelection(text, selectionStartIndex, selectionEndIndex, options);
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     * @param options optional input parameters
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or not greater than startIndex
     *
     * @see #classifyText(CharSequence, int, int)
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options) {
        Utils.validateInput(text, startIndex, endIndex);
        return TextClassification.EMPTY;
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #classifyText(CharSequence, int, int, TextClassification.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or not greater than startIndex
     *
     * @see #classifyText(CharSequence, int, int, TextClassification.Options)
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex) {
        return classifyText(text, startIndex, endIndex, (TextClassification.Options) null);
    }

    /**
     * See {@link #classifyText(CharSequence, int, int, TextClassification.Options)} or
     * {@link #classifyText(CharSequence, int, int)}.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #classifyText(CharSequence, int, int, TextClassification.Options)}. If that method
     * calls this method, a stack overflow error will happen.
     */
    @WorkerThread
    @NonNull
    default TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable LocaleList defaultLocales) {
        final TextClassification.Options options = (defaultLocales != null)
                ? new TextClassification.Options().setDefaultLocales(defaultLocales)
                : null;
        return classifyText(text, startIndex, endIndex, options);
    }

    /**
     * Returns a {@link TextLinks} that may be applied to the text to annotate it with links
     * information.
     *
     * @param text the text to generate annotations for
     * @param options configuration for link generation
     *
     * @throws IllegalArgumentException if text is null
     *
     * @see #generateLinks(CharSequence)
     */
    @WorkerThread
    default TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        Utils.validateInput(text);
        return new TextLinks.Builder(text.toString()).build();
    }

    /**
     * Returns a {@link TextLinks} that may be applied to the text to annotate it with links
     * information.
     *
     * <p><b>NOTE:</b> Do not implement. The default implementation of this method calls
     * {@link #generateLinks(CharSequence, TextLinks.Options)}. If that method calls this method,
     * a stack overflow error will happen.
     *
     * @param text the text to generate annotations for
     *
     * @throws IllegalArgumentException if text is null
     *
     * @see #generateLinks(CharSequence, TextLinks.Options)
     */
    @WorkerThread
    default TextLinks generateLinks(@NonNull CharSequence text) {
        return generateLinks(text, null);
    }

    /**
     * Logs a TextClassifier event.
     *
     * @param source the text classifier used to generate this event
     * @param event the text classifier related event
     * @hide
     */
    @WorkerThread
    default void logEvent(String source, String event) {}

    /**
     * Returns this TextClassifier's settings.
     * @hide
     */
    default TextClassifierConstants getSettings() {
        return TextClassifierConstants.DEFAULT;
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
        static void validateInput(
                @NonNull CharSequence text, int startIndex, int endIndex) {
            Preconditions.checkArgument(text != null);
            Preconditions.checkArgument(startIndex >= 0);
            Preconditions.checkArgument(endIndex <= text.length());
            Preconditions.checkArgument(endIndex > startIndex);
        }

        /**
         * @throws IllegalArgumentException if text is null or options is null
         */
        static void validateInput(@NonNull CharSequence text) {
            Preconditions.checkArgument(text != null);
        }
    }
}
