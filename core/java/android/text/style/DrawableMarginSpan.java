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

import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.text.Spanned;
import android.text.Layout;

public class DrawableMarginSpan
implements LeadingMarginSpan, LineHeightSpan
{
    public DrawableMarginSpan(Drawable b) {
        mDrawable = b;
    }

    public DrawableMarginSpan(Drawable b, int pad) {
        mDrawable = b;
        mPad = pad;
    }

    public int getLeadingMargin(boolean first) {
        return mDrawable.getIntrinsicWidth() + mPad;
    }

    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir,
                                  int top, int baseline, int bottom,
                                  CharSequence text, int start, int end,
                                  boolean first, Layout layout) {
        int st = ((Spanned) text).getSpanStart(this);
        int ix = (int)x;
        int itop = (int)layout.getLineTop(layout.getLineForOffset(st));

        int dw = mDrawable.getIntrinsicWidth();
        int dh = mDrawable.getIntrinsicHeight();

        // XXX What to do about Paint?
        mDrawable.setBounds(ix, itop, ix+dw, itop+dh);
        mDrawable.draw(c);
    }

    public void chooseHeight(CharSequence text, int start, int end,
                             int istartv, int v,
                             Paint.FontMetricsInt fm) {
        if (end == ((Spanned) text).getSpanEnd(this)) {
            int ht = mDrawable.getIntrinsicHeight();

            int need = ht - (v + fm.descent - fm.ascent - istartv);
            if (need > 0)
                fm.descent += need;

            need = ht - (v + fm.bottom - fm.top - istartv);
            if (need > 0)
                fm.bottom += need;
        }
    }

    private Drawable mDrawable;
    private int mPad;
}
