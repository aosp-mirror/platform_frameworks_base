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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;

/**
 * Provides text shaping for multi-styled text.
 *
 * Here is an example of animating text size and letter spacing for simple text.
 * <pre>
 * <code>
 * // In this example, shape the text once for start and end state, then animate between two shape
 * // result without re-shaping in each frame.
 * class SimpleAnimationView @JvmOverloads constructor(
 *         context: Context,
 *         attrs: AttributeSet? = null,
 *         defStyleAttr: Int = 0
 * ) : View(context, attrs, defStyleAttr) {
 *     private val textDir = TextDirectionHeuristics.LOCALE
 *     private val text = "Hello, World."  // The text to be displayed
 *
 *     // Class for keeping drawing parameters.
 *     data class DrawStyle(val textSize: Float, val alpha: Int)
 *
 *     // The start and end text shaping result. This class will animate between these two.
 *     private val start = mutableListOf&lt;Pair&lt;PositionedGlyphs, DrawStyle&gt;&gt;()
 *     private val end = mutableListOf&lt;Pair&lt;PositionedGlyphs, DrawStyle&gt;&gt;()
 *
 *     init {
 *         val startPaint = TextPaint().apply {
 *             alpha = 0 // Alpha only affect text drawing but not text shaping
 *             textSize = 36f // TextSize affect both text shaping and drawing.
 *             letterSpacing = 0f // Letter spacing only affect text shaping but not drawing.
 *         }
 *
 *         val endPaint = TextPaint().apply {
 *             alpha = 255
 *             textSize =128f
 *             letterSpacing = 0.1f
 *         }
 *
 *         TextShaper.shapeText(text, 0, text.length, textDir, startPaint) { _, _, glyphs, paint ->
 *             start.add(Pair(glyphs, DrawStyle(paint.textSize, paint.alpha)))
 *         }
 *         TextShaper.shapeText(text, 0, text.length, textDir, endPaint) { _, _, glyphs, paint ->
 *             end.add(Pair(glyphs, DrawStyle(paint.textSize, paint.alpha)))
 *         }
 *     }
 *
 *     override fun onDraw(canvas: Canvas) {
 *         super.onDraw(canvas)
 *
 *         // Set the baseline to the vertical center of the view.
 *         canvas.translate(0f, height / 2f)
 *
 *         // Assume the number of PositionedGlyphs are the same. If different, you may want to
 *         // animate in a different way, e.g. cross fading.
 *         start.zip(end) { (startGlyphs, startDrawStyle), (endGlyphs, endDrawStyle) ->
 *             // Tween the style and set to paint.
 *             paint.textSize = lerp(startDrawStyle.textSize, endDrawStyle.textSize, progress)
 *             paint.alpha = lerp(startDrawStyle.alpha, endDrawStyle.alpha, progress)
 *
 *             // Assume the number of glyphs are the same. If different, you may want to animate in
 *             // a different way, e.g. cross fading.
 *             require(startGlyphs.glyphCount() == endGlyphs.glyphCount())
 *
 *             if (startGlyphs.glyphCount() == 0) return@zip
 *
 *             var curFont = startGlyphs.getFont(0)
 *             var drawStart = 0
 *             for (i in 1 until startGlyphs.glyphCount()) {
 *                 // Assume the pair of Glyph ID and font is the same. If different, you may want
 *                 // to animate in a different way, e.g. cross fading.
 *                 require(startGlyphs.getGlyphId(i) == endGlyphs.getGlyphId(i))
 *                 require(startGlyphs.getFont(i) === endGlyphs.getFont(i))
 *
 *                 val font = startGlyphs.getFont(i)
 *                 if (curFont != font) {
 *                     drawGlyphs(canvas, startGlyphs, endGlyphs, drawStart, i, curFont, paint)
 *                     curFont = font
 *                     drawStart = i
 *                 }
 *             }
 *             if (drawStart != startGlyphs.glyphCount() - 1) {
 *                 drawGlyphs(canvas, startGlyphs, endGlyphs, drawStart, startGlyphs.glyphCount(),
 *                         curFont, paint)
 *             }
 *         }
 *     }
 *
 *     // Draws Glyphs for the same font run.
 *     private fun drawGlyphs(canvas: Canvas, startGlyph: PositionedGlyphs,
 *                            endGlyph: PositionedGlyphs, start: Int, end: Int, font: Font,
 *                            paint: Paint) {
 *         var cacheIndex = 0
 *         for (i in start until end) {
 *             intArrayCache[cacheIndex] = startGlyph.getGlyphId(i)
 *             // The glyph positions are different from start to end since they are shaped
 *             // with different letter spacing. Use linear interpolation for positions
 *             // during animation.
 *             floatArrayCache[cacheIndex * 2] =
 *                     lerp(startGlyph.getGlyphX(i), endGlyph.getGlyphX(i), progress)
 *             floatArrayCache[cacheIndex * 2 + 1] =
 *                     lerp(startGlyph.getGlyphY(i), endGlyph.getGlyphY(i), progress)
 *             if (cacheIndex == CACHE_SIZE) {  // Cached int array is full. Flashing.
 *                 canvas.drawGlyphs(
 *                         intArrayCache, 0, // glyphID array and its starting offset
 *                         floatArrayCache, 0, // position array and its starting offset
 *                         cacheIndex, // glyph count
 *                         font,
 *                         paint
 *                 )
 *                 cacheIndex = 0
 *             }
 *             cacheIndex++
 *         }
 *         if (cacheIndex != 0) {
 *             canvas.drawGlyphs(
 *                     intArrayCache, 0, // glyphID array and its starting offset
 *                     floatArrayCache, 0, // position array and its starting offset
 *                     cacheIndex, // glyph count
 *                     font,
 *                     paint
 *             )
 *         }
 *     }
 *
 *     // Linear Interpolator
 *     private fun lerp(start: Float, end: Float, t: Float) = start * (1f - t) + end * t
 *     private fun lerp(start: Int, end: Int, t: Float) = (start * (1f - t) + end * t).toInt()
 *
 *     // The animation progress.
 *     var progress: Float = 0f
 *         set(value) {
 *             field = value
 *             invalidate()
 *         }
 *
 *     // working copy of paint.
 *     private val paint = Paint()
 *
 *     // Array cache for reducing allocation during drawing.
 *     private var intArrayCache = IntArray(CACHE_SIZE)
 *     private var floatArrayCache = FloatArray(CACHE_SIZE * 2)
 * }
 * </code>
 * </pre>
 * @see TextRunShaper#shapeTextRun(char[], int, int, int, int, float, float, boolean, Paint)
 * @see TextRunShaper#shapeTextRun(CharSequence, int, int, int, int, float, float, boolean, Paint)
 * @see TextShaper#shapeText(CharSequence, int, int, TextDirectionHeuristic, TextPaint,
 * GlyphsConsumer)
 */
public class TextShaper {
    private TextShaper() {}

    /**
     * An consumer interface for accepting text shape result.
     */
    public interface GlyphsConsumer {
        /**
         * Accept text shape result.
         *
         * The implementation must not keep reference of paint since it will be mutated for the
         * subsequent styles. Also, for saving heap size, keep only necessary members in the
         * {@link TextPaint} instead of copying {@link TextPaint} object.
         *
         * @param start The start index of the shaped text.
         * @param count The length of the shaped text.
         * @param glyphs The shape result.
         * @param paint The paint to be used for drawing.
         */
        void accept(
                @IntRange(from = 0) int start,
                @IntRange(from = 0) int count,
                @NonNull PositionedGlyphs glyphs,
                @NonNull TextPaint paint);
    }

    /**
     * Shape multi-styled text.
     *
     * In the LTR context, the shape result will go from left to right, thus you may want to draw
     * glyphs from left most position of the canvas. In the RTL context, the shape result will go
     * from right to left, thus you may want to draw glyphs from right most position of the canvas.
     *
     * @param text a styled text.
     * @param start a start index of shaping target in the text.
     * @param count a length of shaping target in the text.
     * @param dir a text direction.
     * @param paint a paint
     * @param consumer a consumer of the shape result.
     */
    public static void shapeText(
            @NonNull CharSequence text, @IntRange(from = 0) int start,
            @IntRange(from = 0) int count, @NonNull TextDirectionHeuristic dir,
            @NonNull TextPaint paint, @NonNull GlyphsConsumer consumer) {
        MeasuredParagraph mp = MeasuredParagraph.buildForBidi(
                text, start, start + count, dir, null);
        TextLine tl = TextLine.obtain();
        try {
            tl.set(paint, text, start, start + count,
                    mp.getParagraphDir(),
                    mp.getDirections(0, count),
                    false /* tabstop is not supported */,
                    null,
                    -1, -1, // ellipsis is not supported.
                    false /* fallback line spacing is not used */
            );
            tl.shape(consumer);
        } finally {
            TextLine.recycle(tl);
        }
    }

}
