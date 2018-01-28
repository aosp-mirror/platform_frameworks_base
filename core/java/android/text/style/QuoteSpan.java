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

import android.annotation.ColorInt;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Px;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Parcel;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A span which styles paragraphs by adding a vertical stripe at the beginning of the text
 * (respecting layout direction).
 * <p>
 * A <code>QuoteSpan</code> must be attached from the first character to the last character of a
 * single paragraph, otherwise the span will not be displayed.
 * <p>
 * <code>QuoteSpans</code> allow configuring the following elements:
 * <ul>
 * <li><b>color</b> - the vertical stripe color. By default, the stripe color is 0xff0000ff</li>
 * <li><b>gap width</b> - the distance, in pixels, between the stripe and the paragraph.
 * Default value is 2px.</li>
 * <li><b>stripe width</b> - the width, in pixels, of the stripe. Default value is
 * 2px.</li>
 * </ul>
 * For example, a <code>QuoteSpan</code> using the default values can be constructed like this:
 * <pre>{@code SpannableString string = new SpannableString("Text with quote span on a long line");
 *string.setSpan(new QuoteSpan(), 0, string.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/defaultquotespan.png" />
 * <figcaption><code>QuoteSpan</code> constructed with default values.</figcaption>
 * <p>
 * <p>
 * To construct a <code>QuoteSpan</code> with a green stripe, of 20px in width and a gap width of
 * 40px:
 * <pre>{@code SpannableString string = new SpannableString("Text with quote span on a long line");
 *string.setSpan(new QuoteSpan(Color.GREEN, 20, 40), 0, string.length(),
 *Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/customquotespan.png" />
 * <figcaption>Customized <code>QuoteSpan</code>.</figcaption>
 */
public class QuoteSpan implements LeadingMarginSpan, ParcelableSpan {
    /**
     * Default stripe width in pixels.
     */
    public static final int STANDARD_STRIPE_WIDTH_PX = 2;

    /**
     * Default gap width in pixels.
     */
    public static final int STANDARD_GAP_WIDTH_PX = 2;

    /**
     * Default color for the quote stripe.
     */
    @ColorInt
    public static final int STANDARD_COLOR = 0xff0000ff;

    @ColorInt
    private final int mColor;
    @Px
    private final int mStripeWidth;
    @Px
    private final int mGapWidth;

    /**
     * Creates a {@link QuoteSpan} with the default values.
     */
    public QuoteSpan() {
        this(STANDARD_COLOR, STANDARD_STRIPE_WIDTH_PX, STANDARD_GAP_WIDTH_PX);
    }

    /**
     * Creates a {@link QuoteSpan} based on a color.
     *
     * @param color the color of the quote stripe.
     */
    public QuoteSpan(@ColorInt int color) {
        this(color, STANDARD_STRIPE_WIDTH_PX, STANDARD_GAP_WIDTH_PX);
    }

    /**
     * Creates a {@link QuoteSpan} based on a color, a stripe width and the width of the gap
     * between the stripe and the text.
     *
     * @param color       the color of the quote stripe.
     * @param stripeWidth the width of the stripe.
     * @param gapWidth    the width of the gap between the stripe and the text.
     */
    public QuoteSpan(@ColorInt int color, @IntRange(from = 0) int stripeWidth,
            @IntRange(from = 0) int gapWidth) {
        mColor = color;
        mStripeWidth = stripeWidth;
        mGapWidth = gapWidth;
    }

    /**
     * Create a {@link QuoteSpan} from a parcel.
     */
    public QuoteSpan(@NonNull Parcel src) {
        mColor = src.readInt();
        mStripeWidth = src.readInt();
        mGapWidth = src.readInt();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /**
     * @hide
     */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.QUOTE_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeInt(mColor);
        dest.writeInt(mStripeWidth);
        dest.writeInt(mGapWidth);
    }

    /**
     * Get the color of the quote stripe.
     *
     * @return the color of the quote stripe.
     */
    @ColorInt
    public int getColor() {
        return mColor;
    }

    /**
     * Get the width of the quote stripe.
     *
     * @return the width of the quote stripe.
     */
    public int getStripeWidth() {
        return mStripeWidth;
    }

    /**
     * Get the width of the gap between the stripe and the text.
     *
     * @return the width of the gap between the stripe and the text.
     */
    public int getGapWidth() {
        return mGapWidth;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return mStripeWidth + mGapWidth;
    }

    @Override
    public void drawLeadingMargin(@NonNull Canvas c, @NonNull Paint p, int x, int dir,
            int top, int baseline, int bottom,
            @NonNull CharSequence text, int start, int end,
            boolean first, @NonNull Layout layout) {
        Paint.Style style = p.getStyle();
        int color = p.getColor();

        p.setStyle(Paint.Style.FILL);
        p.setColor(mColor);

        c.drawRect(x, top, x + dir * mStripeWidth, bottom, p);

        p.setStyle(style);
        p.setColor(color);
    }
}
