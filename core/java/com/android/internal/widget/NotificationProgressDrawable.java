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
 * segments, which have non-zero length varying drawing width, and points, which have zero length
 * and fixed size for drawing.
 *
 * @see DrawableSegment
 * @see DrawablePoint
 */
public final class NotificationProgressDrawable extends Drawable {
    private static final String TAG = "NotifProgressDrawable";

    @Nullable
    private BoundsChangeListener mBoundsChangeListener = null;

    private State mState;
    private boolean mMutated;

    private final ArrayList<DrawablePart> mParts = new ArrayList<>();

    private final RectF mSegRectF = new RectF();
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
     * Returns the gap between two segments.
     */
    public float getSegSegGap() {
        return mState.mSegSegGap;
    }

    /**
     * Returns the gap between a segment and a point.
     */
    public float getSegPointGap() {
        return mState.mSegPointGap;
    }

    /**
     * Returns the gap between a segment and a point.
     */
    public float getSegmentMinWidth() {
        return mState.mSegmentMinWidth;
    }

    /**
     * Returns the radius for the points.
     */
    public float getPointRadius() {
        return mState.mPointRadius;
    }

    /**
     * Set the segments and points that constitute the drawable.
     */
    public void setParts(List<DrawablePart> parts) {
        mParts.clear();
        mParts.addAll(parts);

        invalidateSelf();
    }

    /**
     * Set the segments and points that constitute the drawable.
     */
    public void setParts(@NonNull DrawablePart... parts) {
        setParts(Arrays.asList(parts));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final float pointRadius = mState.mPointRadius;
        final float left = (float) getBounds().left;
        final float centerY = (float) getBounds().centerY();

        final int numParts = mParts.size();
        final float pointTop = Math.round(centerY - pointRadius);
        final float pointBottom = Math.round(centerY + pointRadius);
        for (int iPart = 0; iPart < numParts; iPart++) {
            final DrawablePart part = mParts.get(iPart);
            final float start = left + part.mStart;
            final float end = left + part.mEnd;
            if (part instanceof DrawableSegment segment) {
                // No space left to draw the segment
                if (start > end) continue;

                final float radiusY = segment.mFaded ? mState.mFadedSegmentHeight / 2F
                        : mState.mSegmentHeight / 2F;
                final float cornerRadius = mState.mSegmentCornerRadius;

                mFillPaint.setColor(segment.mColor);

                mSegRectF.set(Math.round(start), Math.round(centerY - radiusY), Math.round(end),
                        Math.round(centerY + radiusY));
                canvas.drawRoundRect(mSegRectF, cornerRadius, cornerRadius, mFillPaint);
            } else if (part instanceof DrawablePoint point) {
                // TODO: b/367804171 - actually use a vector asset for the default point
                //  rather than drawing it as a box?
                mPointRectF.set(Math.round(start), pointTop, Math.round(end), pointBottom);
                final float inset = mState.mPointRectInset;
                final float cornerRadius = mState.mPointRectCornerRadius;
                mPointRectF.inset(inset, inset);

                mFillPaint.setColor(point.mColor);

                canvas.drawRoundRect(mPointRectF, cornerRadius, cornerRadius, mFillPaint);
            }
        }
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

    public void setBoundsChangeListener(BoundsChangeListener listener) {
        mBoundsChangeListener = listener;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (mBoundsChangeListener != null) {
            mBoundsChangeListener.onDrawableBoundsChanged();
        }
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

        state.mSegmentMinWidth = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_minWidth, state.mSegmentMinWidth);
        state.mSegmentHeight = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_height, state.mSegmentHeight);
        state.mFadedSegmentHeight = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_fadedHeight,
                state.mFadedSegmentHeight);
        state.mSegmentCornerRadius = a.getDimension(
                R.styleable.NotificationProgressDrawableSegments_cornerRadius,
                state.mSegmentCornerRadius);
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
     * Listener to receive updates about drawable bounds changing
     */
    public interface BoundsChangeListener {
        /** Called when bounds have changed */
        void onDrawableBoundsChanged();
    }

    /**
     * A part of the progress drawable, which is either a {@link DrawableSegment} with non-zero
     * length and varying drawing width, or a {@link DrawablePoint} with zero length and fixed size
     * for drawing.
     */
    public abstract static class DrawablePart {
        // TODO: b/372908709 - maybe rename start/end to left/right, to be consistent with the
        //  bounds rect.
        /** Start position for drawing (in pixels) */
        protected float mStart;
        /** End position for drawing (in pixels) */
        protected float mEnd;
        /** Drawing color. */
        @ColorInt protected final int mColor;

        protected DrawablePart(float start, float end, @ColorInt int color) {
            mStart = start;
            mEnd = end;
            mColor = color;
        }

        public float getStart() {
            return this.mStart;
        }

        public void setStart(float start) {
            mStart = start;
        }

        public float getEnd() {
            return this.mEnd;
        }

        public void setEnd(float end) {
            mEnd = end;
        }

        /** Returns the calculated drawing width of the part */
        public float getWidth() {
            return mEnd - mStart;
        }

        public int getColor() {
            return this.mColor;
        }

        // Needed for unit tests
        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;

            if (other == null || getClass() != other.getClass()) return false;

            DrawablePart that = (DrawablePart) other;
            if (Float.compare(this.mStart, that.mStart) != 0) return false;
            if (Float.compare(this.mEnd, that.mEnd) != 0) return false;
            return this.mColor == that.mColor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mStart, mEnd, mColor);
        }
    }

    /**
     * A segment is a part of the progress bar with non-zero length. For example, it can
     * represent a portion in a navigation journey with certain traffic condition.
     * <p>
     * The start and end positions for drawing a segment are assumed to have been adjusted for
     * the Points and gaps neighboring the segment.
     * </p>
     */
    public static final class DrawableSegment extends DrawablePart {
        /**
         * Whether the segment is faded or not.
         * <p>
         * Faded segments and non-faded segments are drawn with different heights.
         * </p>
         */
        private final boolean mFaded;

        public DrawableSegment(float start, float end, int color) {
            this(start, end, color, false);
        }

        public DrawableSegment(float start, float end, int color, boolean faded) {
            super(start, end, color);
            mFaded = faded;
        }

        @Override
        public String toString() {
            return "Segment(start=" + this.mStart + ", end=" + this.mEnd + ", color=" + this.mColor
                    + ", faded=" + this.mFaded + ')';
        }

        // Needed for unit tests.
        @Override
        public boolean equals(@Nullable Object other) {
            if (!super.equals(other)) return false;

            DrawableSegment that = (DrawableSegment) other;
            return this.mFaded == that.mFaded;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), mFaded);
        }
    }

    /**
     * A point is a part of the progress bar with zero length. Points are designated points within a
     * progress bar to visualize distinct stages or milestones. For example, a stop in a multi-stop
     * ride-share journey.
     */
    public static final class DrawablePoint extends DrawablePart {
        public DrawablePoint(float start, float end, int color) {
            super(start, end, color);
        }

        @Override
        public String toString() {
            return "Point(start=" + this.mStart + ", end=" + this.mEnd + ", color=" + this.mColor
                    + ")";
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
        float mSegmentMinWidth = 0.0f;
        float mSegmentHeight;
        float mFadedSegmentHeight;
        float mSegmentCornerRadius;
        // how big the point icon will be, halved
        float mPointRadius;
        float mPointRectInset;
        float mPointRectCornerRadius;

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
            mSegmentMinWidth = orig.mSegmentMinWidth;
            mSegmentHeight = orig.mSegmentHeight;
            mFadedSegmentHeight = orig.mFadedSegmentHeight;
            mSegmentCornerRadius = orig.mSegmentCornerRadius;
            mPointRadius = orig.mPointRadius;
            mPointRectInset = orig.mPointRectInset;
            mPointRectCornerRadius = orig.mPointRectCornerRadius;

            mThemeAttrs = orig.mThemeAttrs;
            mThemeAttrsSegments = orig.mThemeAttrsSegments;
            mThemeAttrsPoints = orig.mThemeAttrsPoints;

            mDensity = resolveDensity(res, orig.mDensity);
            if (orig.mDensity != mDensity) {
                applyDensityScaling(orig.mDensity, mDensity);
            }
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            if (mSegSegGap > 0) {
                mSegSegGap = scaleFromDensity(
                        mSegSegGap, sourceDensity, targetDensity);
            }
            if (mSegPointGap > 0) {
                mSegPointGap = scaleFromDensity(
                        mSegPointGap, sourceDensity, targetDensity);
            }
            if (mSegmentMinWidth > 0) {
                mSegmentMinWidth = scaleFromDensity(
                        mSegmentMinWidth, sourceDensity, targetDensity);
            }
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
