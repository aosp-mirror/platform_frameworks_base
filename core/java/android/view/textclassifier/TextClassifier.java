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
import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for providing text classification related features.
 *
 * <p>Unless otherwise stated, methods of this interface are blocking operations and you should
 * avoid calling them on the UI thread.
 */
public interface TextClassifier {

    String TYPE_OTHER = "other";
    String TYPE_EMAIL = "email";
    String TYPE_PHONE = "phone";
    String TYPE_ADDRESS = "address";
    String TYPE_URL = "url";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            TYPE_OTHER, TYPE_EMAIL, TYPE_PHONE, TYPE_ADDRESS
    })
    @interface EntityType {}

    /**
     * No-op TextClassifier.
     * This may be used to turn off TextClassifier features.
     */
    TextClassifier NO_OP = new TextClassifier() {

        @Override
        public TextSelection suggestSelection(
                CharSequence text, int selectionStartIndex, int selectionEndIndex) {
            return new TextSelection.Builder(selectionStartIndex, selectionEndIndex).build();
        }

        @Override
        public TextClassificationResult getTextClassificationResult(
                CharSequence text, int startIndex, int endIndex) {
            return TextClassificationResult.EMPTY;
        }

        @Override
        public LinksInfo getLinks(CharSequence text, int linkMask) {
            return LinksInfo.NO_OP;
        }
    };

    /**
     * Returns suggested text selection indices, recognized types and their associated confidence
     * scores. The selections are ordered from highest to lowest scoring.
     *
     * @param text text providing context for the selected text (which is specified
     *      by the sub sequence starting at selectionStartIndex and ending at selectionEndIndex)
     * @param selectionStartIndex start index of the selected part of text
     * @param selectionEndIndex end index of the selected part of text
     *
     * @throws IllegalArgumentException if text is null; selectionStartIndex is negative;
     *      selectionEndIndex is greater than text.length() or less than selectionStartIndex
     */
    @NonNull
    TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex);

    /**
     * Returns a {@link TextClassificationResult} object that can be used to generate a widget for
     * handling the classified text.
     *
     * @param text text providing context for the text to classify (which is specified
     *      by the sub sequence starting at startIndex and ending at endIndex)
     * @param startIndex start index of the text to classify
     * @param endIndex end index of the text to classify
     *
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or less than startIndex
     */
    @NonNull
    TextClassificationResult getTextClassificationResult(
            @NonNull CharSequence text, int startIndex, int endIndex);

    /**
     * Returns a {@link LinksInfo} that may be applied to the text to annotate it with links
     * information.
     *
     * @param text the text to generate annotations for
     * @param linkMask See {@link android.text.util.Linkify} for a list of linkMasks that may be
     *      specified. Subclasses of this interface may specify additional linkMasks
     *
     * @throws IllegalArgumentException if text is null
     */
    LinksInfo getLinks(@NonNull CharSequence text, int linkMask);
}
