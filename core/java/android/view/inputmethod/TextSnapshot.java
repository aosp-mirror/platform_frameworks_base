/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.IntRange;
import android.annotation.NonNull;

import java.util.Objects;

/**
 * An immutable data snapshot of text editing state.
 */
public final class TextSnapshot {
    @NonNull
    private final SurroundingText mSurroundingText;
    @IntRange(from = -1)
    private final int mCompositionStart;
    @IntRange(from = -1)
    private final int mCompositionEnd;
    private final int mCursorCapsMode;

    /**
     * Creates a new instance of {@link TextSnapshot}
     *
     * @param surroundingText {@link SurroundingText} of the current edit field.
     * @param compositionStart The start index of the composing text.
     *                         {@code -1} if there is no composing text.
     * @param compositionEnd The end index of the composing text.
     *                       {@code -1} if there is no composing text.
     * @param cursorCapsMode The capitalization mode of the first character being edited in the
     *                       text.  See {@link EditorInfo#initialCapsMode}.
     * @throws NullPointerException if {@code surroundingText} is {@code null}.
     * @throws IllegalArgumentException if {@code compositionStart} and/or {@code compositionEnd}
     *                                  is less than {@code -1}.
     */
    public TextSnapshot(@NonNull SurroundingText surroundingText,
            @IntRange(from = -1) int compositionStart, @IntRange(from = -1) int compositionEnd,
            int cursorCapsMode) {
        Objects.requireNonNull(surroundingText);
        mSurroundingText = surroundingText;
        if (compositionStart < -1) {
            throw new IllegalArgumentException("compositionStart must be -1 or higher but was "
                    + compositionStart);
        }
        if (compositionEnd < -1) {
            throw new IllegalArgumentException("compositionEnd must be -1 or higher but was "
                    + compositionEnd);
        }
        if (compositionStart == -1 && compositionEnd != -1) {
            throw new IllegalArgumentException("compositionEnd must be -1 if compositionStart is "
                    + "-1 but was " + compositionEnd);
        }
        if (compositionStart != -1 && compositionEnd == -1) {
            throw new IllegalArgumentException("compositionStart must be -1 if compositionEnd is "
                    + "-1 but was " + compositionStart);
        }
        if (compositionStart > compositionEnd) {
            throw new IllegalArgumentException("compositionStart=" + compositionStart + " must be"
                    + " equal to or greater than compositionEnd=" + compositionEnd);
        }
        mCompositionStart = compositionStart;
        mCompositionEnd = compositionEnd;
        mCursorCapsMode = cursorCapsMode;
    }

    /**
     * @return {@link SurroundingText} of the current edit field.
     */
    @NonNull
    public SurroundingText getSurroundingText() {
        return mSurroundingText;
    }

    /**
     * @return The start index of the selection range. {@code -1} if it is not available.
     */
    @IntRange(from = -1)
    public int getSelectionStart() {
        if (mSurroundingText.getOffset() < 0) {
            return -1;
        }
        return mSurroundingText.getSelectionStart() + mSurroundingText.getOffset();
    }

    /**
     * @return The end index of the selection range. {@code -1} if it is not available.
     */
    @IntRange(from = -1)
    public int getSelectionEnd() {
        if (mSurroundingText.getOffset() < 0) {
            return -1;
        }
        return mSurroundingText.getSelectionEnd() + mSurroundingText.getOffset();
    }

    /**
     * @return The end index of the composing text. {@code -1} if there is no composing text.
     */
    @IntRange(from = -1)
    public int getCompositionStart() {
        return mCompositionStart;
    }

    /**
     * @return The end index of the composing text. {@code -1} if there is no composing text.
     */
    @IntRange(from = -1)
    public int getCompositionEnd() {
        return mCompositionEnd;
    }

    /**
     * The capitalization mode of the first character being edited in the text.
     *
     * <p>Values may be any combination of the following values:</p>
     * <ul>
     *     <li>{@link android.text.TextUtils#CAP_MODE_CHARACTERS TextUtils.CAP_MODE_CHARACTERS}</li>
     *     <li>{@link android.text.TextUtils#CAP_MODE_WORDS TextUtils.CAP_MODE_WORDS}</li>
     *     <li>{@link android.text.TextUtils#CAP_MODE_SENTENCES TextUtils.CAP_MODE_SENTENCES}</li>
     * </ul>
     *
     * <p>You should generally just take a non-zero value to mean "start out in caps mode" though.
     * </p>
     * @see EditorInfo#initialCapsMode
     */
    public int getCursorCapsMode() {
        return mCursorCapsMode;
    }
}

