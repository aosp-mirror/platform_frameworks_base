/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextShaper;

import java.util.List;

/**
 * Provides text shaping for multi-styled text.
 *
 * @see TextShaper#shapeTextRun(char[], int, int, int, int, float, float, boolean, Paint)
 * @see TextShaper#shapeTextRun(CharSequence, int, int, int, int, float, float, boolean, Paint)
 * @see StyledTextShaper#shapeText(CharSequence, int, int, TextDirectionHeuristic, TextPaint)
 */
public class StyledTextShaper {
    private StyledTextShaper() {}


    /**
     * Shape multi-styled text.
     *
     * @param text a styled text.
     * @param start a start index of shaping target in the text.
     * @param count a length of shaping target in the text.
     * @param dir a text direction.
     * @param paint a paint
     * @return a shape result.
     */
    public static @NonNull List<PositionedGlyphs> shapeText(
            @NonNull CharSequence text, int start, int count,
            @NonNull TextDirectionHeuristic dir, @NonNull TextPaint paint) {
        MeasuredParagraph mp = MeasuredParagraph.buildForBidi(
                text, start, start + count, dir, null);
        TextLine tl = TextLine.obtain();
        try {
            tl.set(paint, text, start, start + count,
                    mp.getParagraphDir(),
                    mp.getDirections(start, start + count),
                    false /* tabstop is not supported */,
                    null,
                    -1, -1 // ellipsis is not supported.
            );
            return tl.shape();
        } finally {
            TextLine.recycle(tl);
        }
    }

}
