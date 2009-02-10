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

public class QuoteSpan implements LeadingMarginSpan, ParcelableSpan {
    private static final int STRIPE_WIDTH = 2;
    private static final int GAP_WIDTH = 2;

    private final int mColor;

    public QuoteSpan() {
        super();
        mColor = 0xff0000ff;
    }
    
    public QuoteSpan(int color) {
        super();
        mColor = color;
    }

    public QuoteSpan(Parcel src) {
        mColor = src.readInt();
    }
    
    public int getSpanTypeId() {
        return TextUtils.QUOTE_SPAN;
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mColor);
    }

    public int getColor() {
        return mColor;
    }
    
    public int getLeadingMargin(boolean first) {
        return STRIPE_WIDTH + GAP_WIDTH;
    }

    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir,
                                  int top, int baseline, int bottom,
                                  CharSequence text, int start, int end,
                                  boolean first, Layout layout) {
        Paint.Style style = p.getStyle();
        int color = p.getColor();

        p.setStyle(Paint.Style.FILL);
        p.setColor(mColor);

        c.drawRect(x, top, x + dir * STRIPE_WIDTH, bottom, p);

        p.setStyle(style);
        p.setColor(color);
    }
}
