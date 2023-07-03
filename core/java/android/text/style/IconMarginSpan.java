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
import android.annotation.Px;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;

/**
 * Paragraph affecting span, that draws a bitmap at the beginning of a text. The span also allows
 * setting a padding between the bitmap and the text. The default value of the padding is 0px. The
 * span should be attached from the first character of the text.
 * <p>
 * For example, an <code>IconMarginSpan</code> with a bitmap and a padding of 30px can be set
 * like this:
 * <pre>
 * SpannableString string = new SpannableString("Text with icon and padding");
 * string.setSpan(new IconMarginSpan(bitmap, 30), 0, string.length(),
 * Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
 * </pre>
 * <img src="{@docRoot}reference/android/images/text/style/iconmarginspan.png" />
 * <figcaption>Text with <code>IconMarginSpan</code></figcaption>
 * <p>
 *
 * @see DrawableMarginSpan for working with a {@link android.graphics.drawable.Drawable} instead of
 * a {@link Bitmap}.
 */
public class IconMarginSpan implements LeadingMarginSpan, LineHeightSpan {

    @NonNull
    private final Bitmap mBitmap;
    @Px
    private final int mPad;

    /**
     * Creates an {@link IconMarginSpan} from a {@link Bitmap}.
     *
     * @param bitmap bitmap to be rendered at the beginning of the text
     */
    public IconMarginSpan(@NonNull Bitmap bitmap) {
        this(bitmap, 0);
    }

    /**
     * Creates an {@link IconMarginSpan} from a {@link Bitmap}.
     *
     * @param bitmap bitmap to be rendered at the beginning of the text
     * @param pad    padding width, in pixels, between the bitmap and the text
     */
    public IconMarginSpan(@NonNull Bitmap bitmap, @IntRange(from = 0) int pad) {
        mBitmap = bitmap;
        mPad = pad;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return mBitmap.getWidth() + mPad;
    }

    @Override
    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir,
            int top, int baseline, int bottom,
            CharSequence text, int start, int end,
            boolean first, Layout layout) {
        int st = ((Spanned) text).getSpanStart(this);
        int itop = layout.getLineTop(layout.getLineForOffset(st));

        if (dir < 0) {
            x -= mBitmap.getWidth();
        }

        c.drawBitmap(mBitmap, x, itop, p);
    }

    @Override
    public void chooseHeight(CharSequence text, int start, int end,
            int istartv, int v,
            Paint.FontMetricsInt fm) {
        if (end == ((Spanned) text).getSpanEnd(this)) {
            int ht = mBitmap.getHeight();

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
        return "IconMarginSpan{bitmap=" + getBitmap() + ", padding=" + getPadding() + '}';
    }

    /**
     * Returns the bitmap to be used at the beginning of the text
     * @return a bitmap
     */
    @NonNull public Bitmap getBitmap() {
        return mBitmap;
    }

    /**
     * Returns the padding width between the bitmap and the text.
     * @return a padding width in pixels
     */
    @Px public int getPadding() {
        return mPad;
    }
}
