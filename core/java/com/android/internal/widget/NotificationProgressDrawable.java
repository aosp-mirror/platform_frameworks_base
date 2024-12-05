/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.pm.ActivityInfo.Config;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * This is used by NotificationProgressBar for displaying a custom background. It composes of
 * segments, which have non-zero length, and points, which have zero length.
 *
 * @see Segment
 * @see Point
 */
public final class NotificationProgressDrawable extends Drawable {
    private static final String TAG = "NotifProgressDrawable";

    private State mState;
    private boolean mMutated;

    private final ArrayList<Part> mParts = new ArrayList<>();
    private boolean mHasTrackerIcon;

    private final RectF mSegRectF = new RectF();
    private final Rect mPointRect = new Rect();
    private final RectF mPointRectF = new RectF();

    private final Paint mFillPaint = new Paint();

    {
        mFillPaint.setStyle(Paint.Style.FILL);
    }

    private int mAlpha;

    public NotificationProgressDrawable() {
        this(new State(), null);
    }

    /**
     * <p>Set the segment default color for the drawable.</p>
     * <p>Note: changing this property will affect all instances of a drawable loaded from a
     * resource. It is recommended to invoke {@link #mutate()} before changing this property.</p>
     *
     * @param color The color of the stroke
     * @see #mutate()
     */
    public void setSegmentDefaultColor(@ColorInt int color) {
        mState.setSegmentColor(color);
    }

    /**
     * <p>Set the point rect default color for the drawable.</p>
     * <p>Note: changing this property will affect all instances of a drawable loaded from a
     * resource. It is recommended to invoke {@link #mutate()} before changing this property.</p>
     *
     * @param color The color of the point rect
     * @see #mutate()
     */
    public void setPointRectDefaultColor(@ColorInt int color) {
        mState.setPointRectColor(color);
    }

    /**
     * Set the segments and points that constitute the drawable.
     */
    public void setParts(List<Part> parts) {
        mParts.clear();
        mParts.addAll(parts);

        invalidateSelf();
    }

    /**
     * Set the segments and points that constitute the drawable.
     */
    public void setParts(@NonNull Part... parts) {
        setParts(Arrays.asList(parts));
    }

    /**
     * Set whether a tracker is drawn on top of this NotificationProgressDrawable.
     */
    public void setHasTrackerIcon(boolean hasTrackerIcon) {
        if (mHasTrackerIcon != hasTrackerIcon) {
            mHasTrackerIcon = hasTrackerIcon;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final float pointRadius =
                mState.mPointRadius; // how big the point icon will be, halved

        // generally, we will start drawing at (x, y) and end at (x+w, y)
        float x = (float) getBounds().left;
        final float centerY = (float) getBounds().centerY();
        final float totalWidth = (float) getBounds().width();
        float segPointGap = mState.mSegPointGap;

        final int numParts = mParts.size();
        for (int iPart = 0; iPart < numParts; iPart++) {
            final Part part = mParts.get(iPart);
            final Part prevPart = iPart == 0 ? null : mParts.get(iPart - 1);
            final Part nextPart = iPart + 1 == numParts ? null : mParts.get(iPart + 1);
            if (part instanceof Segment segment) {
                // Update the segment-point gap to 2X upon seeing the first faded segment.
                // (Assuming that all segments before are solid, and all segments after are faded.)
                if (segment.mFaded) {
                    segPointGap = mState.mSegPointGap * 2;
                }
                final float segWidth = segment.mFraction * totalWidth;
                // Advance the start position to account for a point immediately prior.
                final float startOffset = getSegStartOffset(prevPart, pointRadius, segPointGap, x);
                final float start = x + startOffset;
                // Retract the end position to account for the padding and a point immediately
                // after.
                final float endOffset = getSegEndOffset(segment, nextPart, pointRadius, segPointGap,
                        mState.mSegSegGap, x + segWidth, totalWidth, mHasTrackerIcon);
                final float end = x + segWidth - endOffset;

                // Advance the current position to account for the segment's fraction of the total
                // width (ignoring offset and padding)
                x += segWidth;

                // No space left to draw the segment
                if (start > end) continue;

                final float radiusY = segment.mFaded ? mState.mFadedSegmentHeight / 2F
                        : mState.mSegmentHeight / 2F;
                final float cornerRadius = mState.mSegmentCornerRadius;

                mFillPaint.setColor(segment.mColor != Color.TRANSPARENT ? segment.mColor
                        : (segment.mFaded ? mState.mFadedSegmentColor : mState.mSegmentColor));

                mSegRectF.set(start, centerY - radiusY, end, centerY + radiusY);
                canvas.drawRoundRect(mSegRectF, cornerRadius, cornerRadius, mFillPaint);
            } else if (part instanceof Point point) {
                final float pointWidth = 2 * pointRadius;
                float start = x - pointRadius;
                if (start < 0) start = 0;
                float end = start + pointWidth;
                if (end > totalWidth) {
                    end = totalWidth;
                    if (totalWidth > pointWidth) start = totalWidth - pointWidth;
                }
                mPointRect.set((int) start, (int) (centerY - pointRadius), (int) end,
                        (int) (centerY + pointRadius));

                if (point.mIcon != null) {
                    point.mIcon.setBounds(mPointRect);
                    point.mIcon.draw(canvas);
                } else {
                    // TODO: b/367804171 - actually use a vector asset for the default point
                    //  rather than drawing it as a box?
                    mPointRectF.set(start, centerY - pointRadius, end, centerY + pointRadius);
                    final float inset = mState.mPointRectInset;
                    final float cornerRadius = mState.mPointRectCornerRadius;
                    mPointRectF.inset(inset, inset);

                    mFillPaint.setColor(point.mColor != Color.TRANSPARENT ? point.mColor
                            : (point.mFaded ? mState.mFadedPointRectColor
                                    : mState.mPointRectColor));

                    canvas.drawRoundRect(mPointRectF, cornerRadius, cornerRadius, mFillPaint);
                }
            }
        }
    }

    private static float getSegStartOffset(Part prevPart, float pointRadius, float segPointGap,
            float startX) {
        if (!(prevPart instanceof Point)) return 0F;
        final float pointOffset = (startX < pointRadius) ? (pointRadius - startX) : 0;
        return pointOffset + pointRadius + segPointGap;
    }

    private static float getSegEndOffset(Segment seg, Part nextPart, float pointRadius,
            float segPointGap,
            float segSegGap, float endX, float totalWidth, boolean hasTrackerIcon) {
        if (nextPart == null) return 0F;
        if (nextPart instanceof Segment nextSeg) {
            if (!seg.mFaded && nextSeg.mFaded) {
                // @see Segment#mFaded
                return hasTrackerIcon ? 0F : segSegGap * 4F;
            }
            return segSegGap;
        }

        final float pointWidth = 2 * pointRadius;
        final float pointOffset = (endX + pointRadius > totalWidth && totalWidth > pointWidth)
                ? (endX + pointRadius - totalWidth) : 0;
        return segPointGap + pointRadius + pointOffset;
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mState.getChangingConfigurations();
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // NO-OP
    }

    @Override
    public int getOpacity() {
        // This method is deprecated. Hence we return UNKNOWN.
        return PixelFormat.UNKNOWN;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Resources.Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        mState.setDensity(resolveDensity(r, 0));

        final TypedArray a = obtainAttributes(r, theme, attrs,
                R.styleable.NotificationProgressDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        updateLocalState();
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final State state = mState;
        if (state == null) {
            return;
        }

        state.setDensity(resolveDensity(t.getResources(), 0));

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.NotificationProgressDrawable);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        applyThemeChildElements(t);

        updateLocalState();
    }

    @Override
    public boolean canApplyTheme() {
        return (mState.canApplyTheme()) || super.canApplyTheme();
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final State state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mSegSegGap = a.getDimension(R.styleable.NotificationProgressDrawable_segSegGap,
                state.mSegSegGap);
        state.mSegPointGap = a.getDimension(R.styleable.NotificationProgressDrawable_segPointGap,
                state.mSegPointGap);
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        TypedArray a;
        int type;

        final int innerDepth = parser.getDepth() + 1;
        int depth;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth
                || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth) {
                continue;
            }

            String name = parser.getName();

            if (name.equals("segments")) {
                a = obtainAttributes(r, theme, attrs,
                        R.styleable.NotificationProgressDrawableSegments);
                updateSegmentsFromTypedArray(a);
                a.recycle();
            } else if (name.equals("points")) {
                a = obtainAttributes(r, theme, attrs,
                        R.styleable.NotificationProgressDrawablePoints);
                updatePointsFromTypedArray(a);
                a.recycle();
            } else {
                Log.w(TAG, "Bad element under NotificationProgressDrawable: " + name);
            }
        }
    }

    private void applyThemeChildElements(Theme t) {
        final State state = mState;

        if (state.mThemeAttrsSegments != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrsSegments, R.styleable.NotificationProgressDrawableSegments);
            updateSegmentsFromTypedArray(a);
            a.recycle();
        }

        if (state.mThemeAttrsPoints != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrsPoints, R.styleable.NotificationProgressDrawablePoints);
            updatePointsFromTypedArray(a);
            a.recycle();
        }
    }

    private void updateSegmentsFromTypedArray(TypedArray a) {
        final State state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrsSegments = a.extractThemeAttrs();

        state.mSegmentHeight = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_height, state.mSegmentHeight);
        state.mFadedSegmentHeight = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_fadedHeight,
                state.mFadedSegmentHeight);
        state.mSegmentCornerRadius = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_cornerRadius,
                state.mSegmentCornerRadius);
        final int color = a.getColor(R.styleable.NotificationProgressDrawableSegments_color,
                state.mSegmentColor);
        setSegmentDefaultColor(color);
    }

    private void updatePointsFromTypedArray(TypedArray a) {
        final State state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrsPoints = a.extractThemeAttrs();

        state.mPointRadius = a.getDimension(R.styleable.NotificationProgressDrawablePoints_radius,
                state.mPointRadius);
        state.mPointRectInset = a.getDimension(R.styleable.NotificationProgressDrawablePoints_inset,
                state.mPointRectInset);
        state.mPointRectCornerRadius = a.getDimension(
                R.styleable.NotificationProgressDrawablePoints_cornerRadius,
                state.mPointRectCornerRadius);
        final int color = a.getColor(R.styleable.NotificationProgressDrawablePoints_color,
                state.mPointRectColor);
        setPointRectDefaultColor(color);
    }

    static int resolveDensity(@Nullable Resources r, int parentDensity) {
        final int densityDpi = r == null ? parentDensity : r.getDisplayMetrics().densityDpi;
        return densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
    }

    /**
     * Scales a floating-point pixel value from the source density to the
     * target density.
     */
    private static float scaleFromDensity(float pixels, int sourceDensity, int targetDensity) {
        return pixels * targetDensity / sourceDensity;
    }

    /**
     * Scales a pixel value from the source density to the target density.
     * <p>
     * Optionally, when {@code isSize} is true, handles the resulting pixel value as a size,
     * which is rounded to the closest positive integer.
     * <p>
     * Note: Iteratively applying density changes could result in drift of the pixel values due
     * to rounding, especially for paddings which are truncated. Therefore it should be avoided.
     * This isn't an issue for the notifications because the inflation pipeline reinflates
     * notification views on density change.
     */
    private static int scaleFromDensity(
            int pixels, int sourceDensity, int targetDensity, boolean isSize) {
        if (pixels == 0 || sourceDensity == targetDensity) {
            return pixels;
        }

        final float result = pixels * targetDensity / (float) sourceDensity;
        if (!isSize) {
            return (int) result;
        }

        final int rounded = Math.round(result);
        if (rounded != 0) {
            return rounded;
        } else if (pixels > 0) {
            return 1;
        } else {
            return -1;
        }
    }

    /**
     * A part of the progress bar, which is either a S{@link Segment} with non-zero length, or a
     * {@link Point} with zero length.
     */
    public interface Part {
    }

    /**
     * A segment is a part of the progress bar with non-zero length. For example, it can
     * represent a portion in a navigation journey with certain traffic condition.
     *
     */
    public static final class Segment implements Part {
        private final float mFraction;
        @ColorInt private final int mColor;
        /** Whether the segment is faded or not.
         * <p>
         *     <pre>
         *     When mFaded is set to true, a combination of the following is done to the segment:
         *       1. The drawing color is mColor with opacity updated to 15%.
         *       2. The segment-point gap is 2X the segment-point gap for non-faded segments.
         *       3. The gap between faded and non-faded segments is:
         *          4X the segment-segment gap, when there is no tracker icon
         *          0, when there is tracker icon
         *     </pre>
         * </p>
         */
        private final boolean mFaded;

        public Segment(float fraction) {
            this(fraction, Color.TRANSPARENT);
        }

        public Segment(float fraction, @ColorInt int color) {
            this(fraction, color, false);
        }

        public Segment(float fraction, @ColorInt int color, boolean faded) {
            mFraction = fraction;
            mColor = color;
            mFaded = faded;
        }

        public float getFraction() {
            return this.mFraction;
        }

        public int getColor() {
            return this.mColor;
        }

        public boolean getFaded() {
            return this.mFaded;
        }

        @Override
        public String toString() {
            return "Segment(fraction=" + this.mFraction + ", color=" + this.mColor + ", faded="
                    + this.mFaded + ')';
        }

        // Needed for unit tests
        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;

            if (other == null || getClass() != other.getClass()) return false;

            Segment that = (Segment) other;
            if (Float.compare(this.mFraction, that.mFraction) != 0) return false;
            if (this.mColor != that.mColor) return false;
            return this.mFaded == that.mFaded;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFraction, mColor, mFaded);
        }
    }

    /**
     * A point is a part of the progress bar with zero length. Points are designated points within a
     * progressbar to visualize distinct stages or milestones. For example, a stop in a multi-stop
     * ride-share journey.
     */
    public static final class Point implements Part {
        @Nullable
        private final Drawable mIcon;
        @ColorInt private final int mColor;
        private final boolean mFaded;

        public Point(@Nullable Drawable icon) {
            this(icon, Color.TRANSPARENT, false);
        }

        public Point(@Nullable Drawable icon, @ColorInt int color) {
            this(icon, color, false);

        }

        public Point(@Nullable Drawable icon, @ColorInt int color, boolean faded) {
            mIcon = icon;
            mColor = color;
            mFaded = faded;
        }

        @Nullable
        public Drawable getIcon() {
            return this.mIcon;
        }

        public int getColor() {
            return this.mColor;
        }

        public boolean getFaded() {
            return this.mFaded;
        }

        @Override
        public String toString() {
            return "Point(icon=" + this.mIcon + ", color=" + this.mColor + ", faded=" + this.mFaded
                    + ")";
        }

        // Needed for unit tests.
        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;

            if (other == null || getClass() != other.getClass()) return false;

            Point that = (Point) other;

            if (!Objects.equals(this.mIcon, that.mIcon)) return false;
            if (this.mColor != that.mColor) return false;
            return this.mFaded == that.mFaded;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIcon, mColor, mFaded);
        }
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState = new State(mState, null);
            updateLocalState();
            mMutated = true;
        }
        return this;
    }

    @Override
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    static final class State extends ConstantState {
        @Config
        int mChangingConfigurations;
        float mSegSegGap = 0.0f;
        float mSegPointGap = 0.0f;
        float mSegmentHeight;
        float mFadedSegmentHeight;
        float mSegmentCornerRadius;
        int mSegmentColor;
        int mFadedSegmentColor;
        float mPointRadius;
        float mPointRectInset;
        float mPointRectCornerRadius;
        int mPointRectColor;
        int mFadedPointRectColor;

        int[] mThemeAttrs;
        int[] mThemeAttrsSegments;
        int[] mThemeAttrsPoints;

        int mDensity = DisplayMetrics.DENSITY_DEFAULT;

        State() {
        }

        State(@NonNull State orig, @Nullable Resources res) {
            mChangingConfigurations = orig.mChangingConfigurations;
            mSegSegGap = orig.mSegSegGap;
            mSegPointGap = orig.mSegPointGap;
            mSegmentHeight = orig.mSegmentHeight;
            mFadedSegmentHeight = orig.mFadedSegmentHeight;
            mSegmentCornerRadius = orig.mSegmentCornerRadius;
            mSegmentColor = orig.mSegmentColor;
            mFadedSegmentColor = orig.mFadedSegmentColor;
            mPointRadius = orig.mPointRadius;
            mPointRectInset = orig.mPointRectInset;
            mPointRectCornerRadius = orig.mPointRectCornerRadius;
            mPointRectColor = orig.mPointRectColor;
            mFadedPointRectColor = orig.mFadedPointRectColor;

            mThemeAttrs = orig.mThemeAttrs;
            mThemeAttrsSegments = orig.mThemeAttrsSegments;
            mThemeAttrsPoints = orig.mThemeAttrsPoints;

            mDensity = resolveDensity(res, orig.mDensity);
            if (orig.mDensity != mDensity) {
                applyDensityScaling(orig.mDensity, mDensity);
            }
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            if (mSegmentHeight > 0) {
                mSegmentHeight = scaleFromDensity(
                        mSegmentHeight, sourceDensity, targetDensity);
            }
            if (mFadedSegmentHeight > 0) {
                mFadedSegmentHeight = scaleFromDensity(
                        mFadedSegmentHeight, sourceDensity, targetDensity);
            }
            if (mSegmentCornerRadius > 0) {
                mSegmentCornerRadius = scaleFromDensity(
                        mSegmentCornerRadius, sourceDensity, targetDensity);
            }
            if (mPointRadius > 0) {
                mPointRadius = scaleFromDensity(
                        mPointRadius, sourceDensity, targetDensity);
            }
            if (mPointRectInset > 0) {
                mPointRectInset = scaleFromDensity(
                        mPointRectInset, sourceDensity, targetDensity);
            }
            if (mPointRectCornerRadius > 0) {
                mPointRectCornerRadius = scaleFromDensity(
                        mPointRectCornerRadius, sourceDensity, targetDensity);
            }
        }

        @NonNull
        @Override
        public Drawable newDrawable() {
            return new NotificationProgressDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(@Nullable Resources res) {
            // If this drawable is being created for a different density,
            // just create a new constant state and call it a day.
            final State state;
            final int density = resolveDensity(res, mDensity);
            if (density != mDensity) {
                state = new State(this, res);
            } else {
                state = this;
            }

            return new NotificationProgressDrawable(state, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null || mThemeAttrsSegments != null || mThemeAttrsPoints != null
                    || super.canApplyTheme();
        }

        public void setDensity(int targetDensity) {
            if (mDensity != targetDensity) {
                final int sourceDensity = mDensity;
                mDensity = targetDensity;

                applyDensityScaling(sourceDensity, targetDensity);
            }
        }

        public void setSegmentColor(int color) {
            mSegmentColor = color;
            mFadedSegmentColor = getFadedColor(color);
        }

        public void setPointRectColor(int color) {
            mPointRectColor = color;
            mFadedPointRectColor = getFadedColor(color);
        }
    }

    /**
     * Get a color with an opacity that's 25% of the input color.
     */
    @ColorInt
    static int getFadedColor(@ColorInt int color) {
        return Color.argb(
                (int) (Color.alpha(color) * 0.25f + 0.5f),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    @Override
    public ConstantState getConstantState() {
        mState.mChangingConfigurations = getChangingConfigurations();
        return mState;
    }

    /**
     * Creates a new themed NotificationProgressDrawable based on the specified constant state.
     * <p>
     * The resulting drawable is guaranteed to have a new constant state.
     *
     * @param state Constant state from which the drawable inherits
     */
    private NotificationProgressDrawable(@NonNull State state, @Nullable Resources res) {
        mState = state;

        updateLocalState();
    }

    private void updateLocalState() {
        // NO-OP
    }
}
