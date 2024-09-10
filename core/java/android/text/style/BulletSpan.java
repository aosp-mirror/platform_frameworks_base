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
import android.annotation.Nullable;
import android.annotation.Px;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Parcel;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.Spanned;
import android.text.TextUtils;

/**
 * A span which styles paragraphs as bullet points (respecting layout direction).
 * <p>
 * BulletSpans must be attached from the first character to the last character of a single
 * paragraph, otherwise the bullet point will not be displayed but the first paragraph encountered
 * will have a leading margin.
 * <p>
 * BulletSpans allow configuring the following elements:
 * <ul>
 * <li><b>gap width</b> - the distance, in pixels, between the bullet point and the paragraph.
 * Default value is 2px.</li>
 * <li><b>color</b> - the bullet point color. By default, the bullet point color is 0 - no color,
 * so it uses the TextView's text color.</li>
 * <li><b>bullet radius</b> - the radius, in pixels, of the bullet point. Default value is
 * 4px.</li>
 * </ul>
 * For example, a BulletSpan using the default values can be constructed like this:
 * <pre>{@code
 *  SpannableString string = new SpannableString("Text with\nBullet point");
 *string.setSpan(new BulletSpan(), 10, 22, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/defaultbulletspan.png" />
 * <figcaption>BulletSpan constructed with default values.</figcaption>
 * <p>
 * <p>
 * To construct a BulletSpan with a gap width of 40px, green bullet point and bullet radius of
 * 20px:
 * <pre>{@code
 *  SpannableString string = new SpannableString("Text with\nBullet point");
 *string.setSpan(new BulletSpan(40, color, 20), 10, 22, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}</pre>
 * <img src="{@docRoot}reference/android/images/text/style/custombulletspan.png" />
 * <figcaption>Customized BulletSpan.</figcaption>
 */
public class BulletSpan implements LeadingMarginSpan, ParcelableSpan {
    // Bullet is slightly bigger to avoid aliasing artifacts on mdpi devices.
    private static final int STANDARD_BULLET_RADIUS = 4;
    public static final int STANDARD_GAP_WIDTH = 2;
    private static final int STANDARD_COLOR = 0;

    @Px
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mGapWidth;
    @Px
    private final int mBulletRadius;
    @ColorInt
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mColor;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final boolean mWantColor;

    /**
     * Creates a {@link BulletSpan} with the default values.
     */
    public BulletSpan() {
        this(STANDARD_GAP_WIDTH, STANDARD_COLOR, false, STANDARD_BULLET_RADIUS);
    }

    /**
     * Creates a {@link BulletSpan} based on a gap width
     *
     * @param gapWidth the distance, in pixels, between the bullet point and the paragraph.
     */
    public BulletSpan(int gapWidth) {
        this(gapWidth, STANDARD_COLOR, false, STANDARD_BULLET_RADIUS);
    }

    /**
     * Creates a {@link BulletSpan} based on a gap width and a color integer.
     *
     * @param gapWidth the distance, in pixels, between the bullet point and the paragraph.
     * @param color    the bullet point color, as a color integer
     * @see android.content.res.Resources#getColor(int, Resources.Theme)
     */
    public BulletSpan(int gapWidth, @ColorInt int color) {
        this(gapWidth, color, true, STANDARD_BULLET_RADIUS);
    }

    /**
     * Creates a {@link BulletSpan} based on a gap width and a color integer.
     *
     * @param gapWidth     the distance, in pixels, between the bullet point and the paragraph.
     * @param color        the bullet point color, as a color integer.
     * @param bulletRadius the radius of the bullet point, in pixels.
     * @see android.content.res.Resources#getColor(int, Resources.Theme)
     */
    public BulletSpan(int gapWidth, @ColorInt int color, @IntRange(from = 0) int bulletRadius) {
        this(gapWidth, color, true, bulletRadius);
    }

    /**
     * @hide
     */
    public BulletSpan(int gapWidth, @ColorInt int color, boolean wantColor,
            @IntRange(from = 0) int bulletRadius) {
        mGapWidth = gapWidth;
        mBulletRadius = bulletRadius;
        mColor = color;
        mWantColor = wantColor;
    }

    /**
     * Creates a {@link BulletSpan} from a parcel.
     */
    public BulletSpan(@NonNull Parcel src) {
        mGapWidth = src.readInt();
        mWantColor = src.readInt() != 0;
        mColor = src.readInt();
        mBulletRadius = src.readInt();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.BULLET_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    @Override
    public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
        dest.writeInt(mGapWidth);
        dest.writeInt(mWantColor ? 1 : 0);
        dest.writeInt(mColor);
        dest.writeInt(mBulletRadius);
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return 2 * mBulletRadius + mGapWidth;
    }

    /**
     * Get the distance, in pixels, between the bullet point and the paragraph.
     *
     * @return the distance, in pixels, between the bullet point and the paragraph.
     */
    public int getGapWidth() {
        return mGapWidth;
    }

    /**
     * Get the radius, in pixels, of the bullet point.
     *
     * @return the radius, in pixels, of the bullet point.
     */
    public int getBulletRadius() {
        return mBulletRadius;
    }

    /**
     * Get the bullet point color.
     *
     * @return the bullet point color
     */
    public int getColor() {
        return mColor;
    }

    /**
     * @return true if the bullet should apply the color.
     * @hide
     */
    public boolean getWantColor() {
        return mWantColor;
    }

    @Override
    public void drawLeadingMargin(@NonNull Canvas canvas, @NonNull Paint paint, int x, int dir,
            int top, int baseline, int bottom,
            @NonNull CharSequence text, int start, int end,
            boolean first, @Nullable Layout layout) {
        if (((Spanned) text).getSpanStart(this) == start) {
            Paint.Style style = paint.getStyle();
            int oldcolor = 0;

            if (mWantColor) {
                oldcolor = paint.getColor();
                paint.setColor(mColor);
            }

            paint.setStyle(Paint.Style.FILL);

            if (layout != null) {
                // "bottom" position might include extra space as a result of line spacing
                // configuration. Subtract extra space in order to show bullet in the vertical
                // center of characters.
                final int line = layout.getLineForOffset(start);
                bottom = bottom - layout.getLineExtra(line);
            }

            final float yPosition = (top + bottom) / 2f;
            final float xPosition = x + dir * mBulletRadius;

            canvas.drawCircle(xPosition, yPosition, mBulletRadius, paint);

            if (mWantColor) {
                paint.setColor(oldcolor);
            }

            paint.setStyle(style);
        }
    }

    @Override
    public String toString() {
        return "BulletSpan{"
                + "gapWidth=" + getGapWidth()
                + ", bulletRadius=" + getBulletRadius()
                + ", color=" + String.format("%08X", getColor())
                + '}';
    }
}
