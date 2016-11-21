/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text;

/**
 * Interface for providing text assistant features.
 */
public interface TextAssistant {

    /**
     * NO_OP TextAssistant. This will act as the default TextAssistant until we implement a
     * TextClassificationManager.
     * @hide
     */
    TextAssistant NO_OP = new TextAssistant() {

        private final TextSelection mTextSelection = new TextSelection();

        @Override
        public TextSelection suggestSelection(
                CharSequence text, int selectionStartIndex, int selectionEndIndex) {
            mTextSelection.mStartIndex = selectionStartIndex;
            mTextSelection.mEndIndex = selectionEndIndex;
            return mTextSelection;
        }

        @Override
        public void addLinks(Spannable text, int linkMask) {}
    };

    /**
     * Returns suggested text selection indices, recognized types and their associated confidence
     * scores. The selections are ordered from highest to lowest scoring.
     */
    TextSelection suggestSelection(
            CharSequence text, int selectionStartIndex, int selectionEndIndex);

    /**
     * Adds assistance clickable spans to the provided text.
     */
    void addLinks(Spannable text, int linkMask);
}
