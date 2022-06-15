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

package android.graphics.text;

import android.annotation.NonNull;
import android.graphics.Paint;
import android.text.TextDirectionHeuristic;
import android.text.TextPaint;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.FastNative;

/**
 * Provides conversion from a text into glyph array.
 *
 * Text shaping is a preprocess for drawing text into canvas with glyphs. The glyph is a most
 * primitive unit of the text drawing, consist of glyph identifier in the font file and its position
 * and style. You can draw the shape result to Canvas by calling Canvas#drawGlyphs.
 *
 * For most of the use cases, {@link android.text.TextShaper} will provide text shaping
 * functionalities needed. {@link TextRunShaper} is a lower level API that is used by
 * {@link android.text.TextShaper}.
 *
 * @see TextRunShaper#shapeTextRun(CharSequence, int, int, int, int, float, float, boolean, Paint)
 * @see TextRunShaper#shapeTextRun(char[], int, int, int, int, float, float, boolean, Paint)
 * @see android.text.TextShaper#shapeText(CharSequence, int, int, TextDirectionHeuristic, TextPaint,
 * TextShaper.GlyphsConsumer)
 */
public class TextRunShaper {
    private TextRunShaper() {}  // Do not instantiate

    /**
     * Shape non-styled text.
     *
     * This function shapes the text of the given range under the context of given context range.
     * Some script, e.g. Arabic or Devanagari, changes letter shape based on its location or
     * surrounding characters.
     *
     * @param text a text buffer to be shaped
     * @param start a start index of shaping target in the buffer.
     * @param count a length of shaping target in the buffer.
     * @param contextStart a start index of context used for shaping in the buffer.
     * @param contextCount a length of context used for shaping in the buffer.
     * @param xOffset an additional amount of x offset of the result glyphs.
     * @param yOffset an additional amount of y offset of the result glyphs.
     * @param isRtl true if this text is shaped for RTL direction, false otherwise.
     * @param paint a paint used for shaping text.
     * @return a shape result.
     */
    @NonNull
    public static PositionedGlyphs shapeTextRun(
            @NonNull char[] text, int start, int count, int contextStart, int contextCount,
            float xOffset, float yOffset, boolean isRtl, @NonNull Paint paint) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(paint);
        return new PositionedGlyphs(
                nativeShapeTextRun(text, start, count, contextStart, contextCount, isRtl,
                        paint.getNativeInstance()),
                xOffset, yOffset);
    }

    /**
     * Shape non-styled text.
     *
     * This function shapes the text of the given range under the context of given context range.
     * Some script, e.g. Arabic or Devanagari, changes letter shape based on its location or
     * surrounding characters.
     *
     * @param text a text buffer to be shaped. Any styled spans stored in this text are ignored.
     * @param start a start index of shaping target in the buffer.
     * @param count a length of shaping target in the buffer.
     * @param contextStart a start index of context used for shaping in the buffer.
     * @param contextCount a length of context used for shaping in the buffer.
     * @param xOffset an additional amount of x offset of the result glyphs.
     * @param yOffset an additional amount of y offset of the result glyphs.
     * @param isRtl true if this text is shaped for RTL direction, false otherwise.
     * @param paint a paint used for shaping text.
     * @return a shape result
     */
    @NonNull
    public static PositionedGlyphs shapeTextRun(
            @NonNull CharSequence text, int start, int count, int contextStart, int contextCount,
            float xOffset, float yOffset, boolean isRtl, @NonNull Paint paint) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(paint);
        if (text instanceof String) {
            return new PositionedGlyphs(
                    nativeShapeTextRun(
                            (String) text, start, count, contextStart, contextCount, isRtl,
                            paint.getNativeInstance()),
                    xOffset, yOffset);
        } else {
            char[] buf = new char[contextCount];
            TextUtils.getChars(text, contextStart, contextStart + contextCount, buf, 0);
            return new PositionedGlyphs(
                    nativeShapeTextRun(
                            buf, start - contextStart, count,
                            0, contextCount, isRtl, paint.getNativeInstance()),
                    xOffset, yOffset);
        }
    }

    @FastNative
    private static native long nativeShapeTextRun(
            char[] text, int start, int count, int contextStart, int contextCount,
            boolean isRtl, long nativePaint);

    @FastNative
    private static native long nativeShapeTextRun(
            String text, int start, int count, int contextStart, int contextCount,
            boolean isRtl, long nativePaint);

}
