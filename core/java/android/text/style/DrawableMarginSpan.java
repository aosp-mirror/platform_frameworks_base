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

import android.annotation.NonNull;
import android.annotation.Px;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spanned;

/**
 * A span which adds a drawable and a padding to the paragraph it's attached to.
 * <p>
 * If the height of the drawable is bigger than the height of the line it's attached to then the
 * line height is increased to fit the drawable. <code>DrawableMarginSpan</code> allows setting a
 * padding between the drawable and the text. The default value is 0. The span must be set from the
 * beginning of the text, otherwise either the span won't be rendered or it will be rendered
 * incorrectly.
 * <p>
 * For example, a drawable and a padding of 20px can be added like this:
 * <pre>{@code SpannableString string = new SpannableString("Text with a drawable.");
 * string.setSpan(new DrawableMarginSpan(drawable, 20), 0, string.length(),
 * Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/drawablemarginspan.png" />
 * <figcaption>Text with a drawable and a padding.</figcaption>
 * <p>
 *
 * @see IconMarginSpan for working with a {@link android.graphics.Bitmap} instead of
 * a {@link Drawable}.
 */
public class DrawableMarginSpan implements LeadingMarginSpan, LineHeightSpan {
    private static final int STANDARD_PAD_WIDTH = 0;

    @NonNull
    private final Drawable mDrawable;
    @Px
    private final int mPad;

    /**
     * Creates a {@link DrawableMarginSpan} from a {@link Drawable}. The pad width will be 0.
     *
     * @param drawable the drawable to be added
     */
    public DrawableMarginSpan(@NonNull Drawable drawable) {
        this(drawable, STANDARD_PAD_WIDTH);
    }

    /**
     * Creates a {@link DrawableMarginSpan} from a {@link Drawable} and a padding, in pixels.
     *
     * @param drawable the drawable to be added
     * @param pad      the distance between the drawable and the text
     */
    public DrawableMarginSpan(@NonNull Drawable drawable, int pad) {
        mDrawable = drawable;
        mPad = pad;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return mDrawable.getIntrinsicWidth() + mPad;
    }

    @Override
    public void drawLeadingMargin(@NonNull Canvas c, @NonNull Paint p, int x, int dir,
            int top, int baseline, int bottom,
            @NonNull CharSequence text, int start, int end,
            boolean first, @NonNull Layout layout) {
        int st = ((Spanned) text).getSpanStart(this);
        int ix = (int) x;
        int itop = (int) layout.getLineTop(layout.getLineForOffset(st));

        int dw = mDrawable.getIntrinsicWidth();
        int dh = mDrawable.getIntrinsicHeight();

        // XXX What to do about Paint?
        mDrawable.setBounds(ix, itop, ix + dw, itop + dh);
        mDrawable.draw(c);
    }

    @Override
    public void chooseHeight(@NonNull CharSequence text, int start, int end,
            int istartv, int v,
            @NonNull Paint.FontMetricsInt fm) {
        if (end == ((Spanned) text).getSpanEnd(this)) {
            int ht = mDrawable.getIntrinsicHeight();

            int need = ht - (v + fm.descent - fm.ascent - istartv);
            if (need > 0) {
                fm.descent += need;
            }

            need = ht - (v + fm.bottom - fm.top - istartv);
            if (need > 0) {
                fm.bottom += need;
            }
        }
    }

    @Override
    public String toString() {
        return "DrawableMarginSpan{drawable=" + mDrawable + ", padding=" + mPad + '}';
    }

    /**
     * Returns the drawable used.
     * @return a drawable
     */
    @NonNull public Drawable getDrawable() {
        return mDrawable;
    }

    /**
     * Returns a distance between the drawable and text in pixel.
     * @return a distance pixel from the text
     */
    @Px public int getPadding() {
        return mPad;
    }
}
