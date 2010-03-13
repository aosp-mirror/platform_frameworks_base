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
import android.graphics.Canvas;
import android.os.Parcel;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A paragraph style affecting the leading margin. There can be multiple leading
 * margin spans on a single paragraph; they will be rendered in order, each
 * adding its margin to the ones before it. The leading margin is on the right
 * for lines in a right-to-left paragraph.
 */
public interface LeadingMarginSpan
extends ParagraphStyle
{
    /**
     * Returns the amount by which to adjust the leading margin. Positive values
     * move away from the leading edge of the paragraph, negative values move
     * towards it.
     * 
     * @param first true if the request is for the first line of a paragraph,
     * false for subsequent lines
     * @return the offset for the margin.
     */
    public int getLeadingMargin(boolean first);

    /**
     * Renders the leading margin.  This is called before the margin has been
     * adjusted by the value returned by {@link #getLeadingMargin(boolean)}.
     * 
     * @param c the canvas
     * @param p the paint. The this should be left unchanged on exit.
     * @param x the current position of the margin
     * @param dir the base direction of the paragraph; if negative, the margin
     * is to the right of the text, otherwise it is to the left.
     * @param top the top of the line
     * @param baseline the baseline of the line
     * @param bottom the bottom of the line
     * @param text the text
     * @param start the start of the line
     * @param end the end of the line
     * @param first true if this is the first line of its paragraph
     * @param layout the layout containing this line
     */
    public void drawLeadingMargin(Canvas c, Paint p,
                                  int x, int dir,
                                  int top, int baseline, int bottom,
                                  CharSequence text, int start, int end,
                                  boolean first, Layout layout);


    /**
     * An extended version of {@link LeadingMarginSpan}, which allows
     * the implementor to specify the number of lines of text to which
     * this object is attached that the "first line of paragraph" margin
     * width will be applied to.
     */
    public interface LeadingMarginSpan2 extends LeadingMarginSpan, WrapTogetherSpan {
        /**
         * Returns the number of lines of text to which this object is
         * attached that the "first line" margin will apply to.
         * Note that if this returns N, the first N lines of the region,
         * not the first N lines of each paragraph, will be given the
         * special margin width.
         */
        public int getLeadingMarginLineCount();
    };

    /**
     * The standard implementation of LeadingMarginSpan, which adjusts the
     * margin but does not do any rendering.
     */
    public static class Standard implements LeadingMarginSpan, ParcelableSpan {
        private final int mFirst, mRest;
        
        /**
         * Constructor taking separate indents for the first and subsequent
         * lines.
         * 
         * @param first the indent for the first line of the paragraph
         * @param rest the indent for the remaining lines of the paragraph
         */
        public Standard(int first, int rest) {
            mFirst = first;
            mRest = rest;
        }

        /**
         * Constructor taking an indent for all lines.
         * @param every the indent of each line
         */
        public Standard(int every) {
            this(every, every);
        }

        public Standard(Parcel src) {
            mFirst = src.readInt();
            mRest = src.readInt();
        }
        
        public int getSpanTypeId() {
            return TextUtils.LEADING_MARGIN_SPAN;
        }
        
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mFirst);
            dest.writeInt(mRest);
        }

        public int getLeadingMargin(boolean first) {
            return first ? mFirst : mRest;
        }

        public void drawLeadingMargin(Canvas c, Paint p,
                                      int x, int dir,
                                      int top, int baseline, int bottom,
                                      CharSequence text, int start, int end,
                                      boolean first, Layout layout) {
            ;
        }
    }
}
