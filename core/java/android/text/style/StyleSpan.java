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
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.FontStyle;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Span that allows setting the style of the text it's attached to.
 * Possible styles are: {@link Typeface#NORMAL}, {@link Typeface#BOLD}, {@link Typeface#ITALIC} and
 * {@link Typeface#BOLD_ITALIC}.
 * <p>
 * Note that styles are cumulative -- if both bold and italic are set in
 * separate spans, or if the base style is bold and a span calls for italic,
 * you get bold italic.  You can't turn off a style from the base style.
 * <p>
 * For example, the <code>StyleSpan</code> can be used like this:
 * <pre>
 * SpannableString string = new SpannableString("Bold and italic text");
 * string.setSpan(new StyleSpan(Typeface.BOLD), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
 * string.setSpan(new StyleSpan(Typeface.ITALIC), 9, 15, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
 * </pre>
 * <img src="{@docRoot}reference/android/images/text/style/stylespan.png" />
 * <figcaption>Text styled bold and italic with the <code>StyleSpan</code>.</figcaption>
 */
public class StyleSpan extends MetricAffectingSpan implements ParcelableSpan {

    private final int mStyle;
    private final int mFontWeightAdjustment;

    /**
     * Creates a {@link StyleSpan} from a style.
     *
     * @param style An integer constant describing the style for this span. Examples
     *              include bold, italic, and normal. Values are constants defined
     *              in {@link Typeface}.
     */
    public StyleSpan(int style) {
        this(style, Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED);
    }

    /**
     * Creates a {@link StyleSpan} from a style and font weight adjustment.
     *
     * @param style An integer constant describing the style for this span. Examples
     *              include bold, italic, and normal. Values are constants defined
     *              in {@link Typeface}.
     * @param fontWeightAdjustment An integer describing the adjustment to be made to the font
     *              weight. This is added to the value of the current weight returned by
     *              {@link Typeface#getWeight()}.
     * @see Configuration#fontWeightAdjustment This is the adjustment in text font weight
     * that is used to reflect the current user's preference for increasing font weight.
     */
    public StyleSpan(@Typeface.Style int style, int fontWeightAdjustment) {
        mStyle = style;
        mFontWeightAdjustment = fontWeightAdjustment;
    }

    /**
     * Creates a {@link StyleSpan} from a parcel.
     *
     * @param src the parcel
     */
    public StyleSpan(@NonNull Parcel src) {
        mStyle = src.readInt();
        mFontWeightAdjustment = src.readInt();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.STYLE_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    @Override
    public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStyle);
        dest.writeInt(mFontWeightAdjustment);
    }

    /**
     * Returns the style constant defined in {@link Typeface}.
     */
    public int getStyle() {
        return mStyle;
    }

    /**
     * Returns the font weight adjustment specified by this span.
     * <p>
     * This can be {@link Configuration#FONT_WEIGHT_ADJUSTMENT_UNDEFINED}. This is added to the
     * value of the current weight returned by {@link Typeface#getWeight()}.
     */
    public int getFontWeightAdjustment() {
        return mFontWeightAdjustment;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        apply(ds, mStyle, mFontWeightAdjustment);
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        apply(paint, mStyle, mFontWeightAdjustment);
    }

    private static void apply(Paint paint, int style, int fontWeightAdjustment) {
        int oldStyle;

        Typeface old = paint.getTypeface();
        if (old == null) {
            oldStyle = 0;
        } else {
            oldStyle = old.getStyle();
        }

        int want = oldStyle | style;

        Typeface tf;
        if (old == null) {
            tf = Typeface.defaultFromStyle(want);
        } else {
            tf = Typeface.create(old, want);
        }

        // Base typeface may already be bolded by auto bold. Bold further.
        if ((style & Typeface.BOLD) != 0) {
            if (fontWeightAdjustment != 0
                    && fontWeightAdjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) {
                int newWeight = Math.min(
                        Math.max(tf.getWeight() + fontWeightAdjustment, FontStyle.FONT_WEIGHT_MIN),
                        FontStyle.FONT_WEIGHT_MAX);
                boolean italic = (want & Typeface.ITALIC) != 0;
                tf = Typeface.create(tf, newWeight, italic);
            }
        }

        int fake = want & ~tf.getStyle();

        if ((fake & Typeface.BOLD) != 0) {
            paint.setFakeBoldText(true);
        }

        if ((fake & Typeface.ITALIC) != 0) {
            paint.setTextSkewX(-0.25f);
        }

        paint.setTypeface(tf);
    }

    @Override
    public String toString() {
        return "StyleSpan{"
                + "style=" + getStyle()
                + ", fontWeightAdjustment=" + getFontWeightAdjustment()
                + '}';
    }
}
