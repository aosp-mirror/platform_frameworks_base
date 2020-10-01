/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.style;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;

public abstract class ReplacementSpan extends MetricAffectingSpan {

    private CharSequence mContentDescription = null;

    /**
     * Returns the width of the span. Extending classes can set the height of the span by updating
     * attributes of {@link android.graphics.Paint.FontMetricsInt}. If the span covers the whole
     * text, and the height is not set,
     * {@link #draw(Canvas, CharSequence, int, int, float, int, int, int, Paint)} will not be
     * called for the span.
     *
     * @param paint Paint instance.
     * @param text Current text.
     * @param start Start character index for span.
     * @param end End character index for span.
     * @param fm Font metrics, can be null.
     * @return Width of the span.
     */
    public abstract int getSize(@NonNull Paint paint, CharSequence text,
                        @IntRange(from = 0) int start, @IntRange(from = 0) int end,
                        @Nullable Paint.FontMetricsInt fm);

    /**
     * Draws the span into the canvas.
     *
     * @param canvas Canvas into which the span should be rendered.
     * @param text Current text.
     * @param start Start character index for span.
     * @param end End character index for span.
     * @param x Edge of the replacement closest to the leading margin.
     * @param top Top of the line.
     * @param y Baseline.
     * @param bottom Bottom of the line.
     * @param paint Paint instance.
     */
    public abstract void draw(@NonNull Canvas canvas, CharSequence text,
                              @IntRange(from = 0) int start, @IntRange(from = 0) int end, float x,
                              int top, int y, int bottom, @NonNull Paint paint);

    /**
     * Gets a brief description of this ReplacementSpan for use in accessibility support.
     *
     * @return The content description.
     */
    @Nullable
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the specific content description into ReplacementSpan.
     * ReplacementSpans are shared with accessibility services,
     * but only the content description is available from them.
     *
     * @param contentDescription content description. The default value is null.
     */
    public void setContentDescription(@Nullable CharSequence contentDescription) {
        mContentDescription = contentDescription;
    }

    /**
     * This method does nothing, since ReplacementSpans are measured
     * explicitly instead of affecting Paint properties.
     */
    public void updateMeasureState(TextPaint p) { }

    /**
     * This method does nothing, since ReplacementSpans are drawn
     * explicitly instead of affecting Paint properties.
     */
    public void updateDrawState(TextPaint ds) { }
}
