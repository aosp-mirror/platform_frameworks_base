/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.view.MotionEvent;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Base class for Stylus handwriting gesture.
 *
 * During a stylus handwriting session, user can perform a stylus gesture operation like
 * {@link SelectGesture}, {@link DeleteGesture}, {@link InsertGesture} on an
 * area of text. IME is responsible for listening to Stylus {@link MotionEvent} using
 * {@link InputMethodService#onStylusHandwritingMotionEvent} and interpret if it can translate to a
 * gesture operation.
 * While creating Gesture operations {@link SelectGesture}, {@link DeleteGesture},
 * , {@code Granularity} helps pick the correct granular level of text like word level
 * {@link #GRANULARITY_WORD}, or character level {@link #GRANULARITY_CHARACTER}.
 *
 * @see InputConnection#performHandwritingGesture(HandwritingGesture, Executor, IntConsumer)
 * @see InputMethodService#onStartStylusHandwriting()
 */
public abstract class HandwritingGesture {

    HandwritingGesture() {}

    static final int GRANULARITY_UNDEFINED = 0;

    /**
     * Operate text per word basis. e.g. if selection includes width-wise center of the word,
     * whole word is selected.
     * <p> Strategy of operating at a granular level is maintained in the UI toolkit.
     *     A character/word/line is included if its center is within the gesture rectangle.
     *     e.g. if a selection {@link RectF} with {@link #GRANULARITY_WORD} includes width-wise
     *     center of the word, it should be selected.
     *     Similarly, text in a line should be included in the operation if rectangle includes
     *     line height center.</p>
     * Refer to https://www.unicode.org/reports/tr29/#Word_Boundaries for more detail on how word
     * breaks are decided.
     */
    public static final int GRANULARITY_WORD = 1;

    /**
     * Operate on text per character basis. i.e. each character is selected based on its
     * intersection with selection rectangle.
     * <p> Strategy of operating at a granular level is maintained in the UI toolkit.
     *     A character/word/line is included if its center is within the gesture rectangle.
     *     e.g. if a selection {@link RectF} with {@link #GRANULARITY_CHARACTER} includes width-wise
     *     center of the character, it should be selected.
     *     Similarly, text in a line should be included in the operation if rectangle includes
     *     line height center.</p>
     */
    public static final int GRANULARITY_CHARACTER = 2;

    /**
     * Granular level on which text should be operated.
     */
    @IntDef({GRANULARITY_CHARACTER, GRANULARITY_WORD})
    @interface Granularity {}

    @Nullable
    String mFallbackText;

    /**
     * The fallback text that will be committed at current cursor position if there is no applicable
     * text beneath the area of gesture.
     * For example, select can fail if gesture is drawn over area that has no text beneath.
     * example 2: join can fail if the gesture is drawn over text but there is no whitespace.
     */
    @Nullable
    public String getFallbackText() {
        return mFallbackText;
    }
}
