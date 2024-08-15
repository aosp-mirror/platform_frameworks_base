/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.graphics.Color;
import android.text.style.CharacterStyle;

/**
 * Finds the foreground text color for the given Spanned text so you can iterate through each color
 * change.
 *
 * @hide
 */
public class SpanColors {
    public static final @ColorInt int NO_COLOR_FOUND = Color.TRANSPARENT;

    private final SpanSet<CharacterStyle> mCharacterStyleSpanSet =
            new SpanSet<>(CharacterStyle.class);
    @Nullable private TextPaint mWorkPaint;

    public SpanColors() {}

    /**
     * Init for the given text
     *
     * @param workPaint A temporary TextPaint object that will be used to calculate the colors. The
     *                  paint properties will be mutated on calls to {@link #getColorAt(int)} so
     *                  make sure to reset it before you use it for something else.
     * @param spanned the text to examine
     * @param start index to start at
     * @param end index of the end
     */
    public void init(TextPaint workPaint, Spanned spanned, int start, int end) {
        mWorkPaint = workPaint;
        mCharacterStyleSpanSet.init(spanned, start, end);
    }

    /**
     * Removes all internal references to the spans to avoid memory leaks.
     */
    public void recycle() {
        mWorkPaint = null;
        mCharacterStyleSpanSet.recycle();
    }

    /**
     * Calculates the foreground color of the text at the given character index.
     *
     * <p>You must call {@link #init(TextPaint, Spanned, int, int)} before calling this
     */
    public @ColorInt int getColorAt(int index) {
        var finalColor = NO_COLOR_FOUND;
        // Reset the paint so if we get a CharacterStyle that doesn't actually specify color,
        // (like UnderlineSpan), we still return no color found.
        mWorkPaint.setColor(finalColor);
        for (int k = 0; k < mCharacterStyleSpanSet.numberOfSpans; k++) {
            if ((index >= mCharacterStyleSpanSet.spanStarts[k])
                    && (index <= mCharacterStyleSpanSet.spanEnds[k])) {
                final CharacterStyle span = mCharacterStyleSpanSet.spans[k];
                span.updateDrawState(mWorkPaint);

                finalColor = calculateFinalColor(mWorkPaint);
            }
        }
        return finalColor;
    }

    private @ColorInt int calculateFinalColor(TextPaint workPaint) {
        // TODO: can we figure out what the getColorFilter() will do?
        //  if so, we also need to reset colorFilter before the loop in getColorAt()
        return workPaint.getColor();
    }
}
