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

import android.graphics.Paint;
import android.text.TextPaint;

/**
 * The classes that affect the height of the line should implement this interface.
 */
public interface LineHeightSpan extends ParagraphStyle, WrapTogetherSpan {
    /**
     * Classes that implement this should define how the height is being calculated.
     *
     * @param text       the text
     * @param start      the start of the line
     * @param end        the end of the line
     * @param spanstartv the start of the span
     * @param lineHeight the line height
     * @param fm         font metrics of the paint, in integers
     */
    public void chooseHeight(CharSequence text, int start, int end,
            int spanstartv, int lineHeight,
            Paint.FontMetricsInt fm);

    /**
     * The classes that affect the height of the line with respect to density, should implement this
     * interface.
     */
    public interface WithDensity extends LineHeightSpan {

        /**
         * Classes that implement this should define how the height is being calculated.
         *
         * @param text       the text
         * @param start      the start of the line
         * @param end        the end of the line
         * @param spanstartv the start of the span
         * @param lineHeight the line height
         * @param paint      the paint
         */
        public void chooseHeight(CharSequence text, int start, int end,
                int spanstartv, int lineHeight,
                Paint.FontMetricsInt fm, TextPaint paint);
    }
}
