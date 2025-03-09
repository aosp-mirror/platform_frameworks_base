/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import static android.util.MathUtils.acos;

import static java.lang.Math.sin;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.view.flags.Flags;

/**
 * Helper class for drawing round scroll bars on round Wear devices.
 *
 * @hide
 */
public class RoundScrollbarRenderer {
    /** @hide */
    public static final String BLUECHIP_ENABLED_SYSPROP = "persist.cw_build.bluechip.enabled";

    // The range of the scrollbar position represented as an angle in degrees.
    private static final float SCROLLBAR_ANGLE_RANGE = 28.8f;
    private static final float MAX_SCROLLBAR_ANGLE_SWIPE = 26.3f; // 90%
    private static final float MIN_SCROLLBAR_ANGLE_SWIPE = 3.1f; // 10%
    private static final float THUMB_WIDTH_DP = 4f;
    private static final float OUTER_PADDING_DP = 2f;
    private static final int DEFAULT_THUMB_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_TRACK_COLOR = 0x4CFFFFFF;

    // Rate at which the scrollbar will resize itself when the size of the view changes
    private static final float RESIZING_RATE = 0.8f;
    // Threshold at which the scrollbar will stop resizing smoothly and jump to the correct size
    private static final int RESIZING_THRESHOLD_PX = 20;

    private final Paint mThumbPaint = new Paint();
    private final Paint mTrackPaint = new Paint();
    private final RectF mRect = new RectF();
    private final View mParent;
    private final float mInset;

    private float mPreviousMaxScroll = 0;
    private float mMaxScrollDiff = 0;
    private float mPreviousCurrentScroll = 0;
    private float mCurrentScrollDiff = 0;
    private float mThumbStrokeWidthAsDegrees = 0;
    private boolean mDrawToLeft;
    private boolean mUseRefactoredRoundScrollbar;

    public RoundScrollbarRenderer(View parent) {
        // Paints for the round scrollbar.
        // Set up the thumb paint
        mThumbPaint.setAntiAlias(true);
        mThumbPaint.setStrokeCap(Paint.Cap.ROUND);
        mThumbPaint.setStyle(Paint.Style.STROKE);

        // Set up the track paint
        mTrackPaint.setAntiAlias(true);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setStyle(Paint.Style.STROKE);

        mParent = parent;

        // Fetch the resource indicating the thickness of CircularDisplayMask, rounding in the same
        // way WindowManagerService.showCircularMask does. The scroll bar is inset by this amount so
        // that it doesn't get clipped.
        int maskThickness =
                parent.getContext()
                        .getResources()
                        .getDimensionPixelSize(
                                com.android.internal.R.dimen.circular_display_mask_thickness);

        float thumbWidth = dpToPx(THUMB_WIDTH_DP);
        mThumbPaint.setStrokeWidth(thumbWidth);
        mTrackPaint.setStrokeWidth(thumbWidth);
        mInset = thumbWidth / 2 + maskThickness;

        mUseRefactoredRoundScrollbar =
                Flags.useRefactoredRoundScrollbar()
                        && SystemProperties.getBoolean(BLUECHIP_ENABLED_SYSPROP, false);
    }

    private float computeScrollExtent(float scrollExtent, float maxScroll) {
        if (scrollExtent <= 0) {
            if (!mParent.canScrollVertically(1) && !mParent.canScrollVertically(-1)) {
                return -1f;
            } else {
                return 0f;
            }
        } else if (maxScroll <= scrollExtent) {
            return -1f;
        }
        return scrollExtent;
    }

    private void resizeGradually(float maxScroll, float newScroll) {
        // Make changes to the VerticalScrollRange happen gradually
        if (Math.abs(maxScroll - mPreviousMaxScroll) > RESIZING_THRESHOLD_PX
                && mPreviousMaxScroll != 0) {
            mMaxScrollDiff += maxScroll - mPreviousMaxScroll;
            mCurrentScrollDiff += newScroll - mPreviousCurrentScroll;
        }

        mPreviousMaxScroll = maxScroll;
        mPreviousCurrentScroll = newScroll;

        if (Math.abs(mMaxScrollDiff) > RESIZING_THRESHOLD_PX
                || Math.abs(mCurrentScrollDiff) > RESIZING_THRESHOLD_PX) {
            mMaxScrollDiff *= RESIZING_RATE;
            mCurrentScrollDiff *= RESIZING_RATE;
        } else {
            mMaxScrollDiff = 0;
            mCurrentScrollDiff = 0;
        }
    }

    public void drawRoundScrollbars(Canvas canvas, float alpha, Rect bounds, boolean drawToLeft) {
        if (alpha == 0) {
            return;
        }
        // Get information about the current scroll state of the parent view.
        float maxScroll = mParent.computeVerticalScrollRange();
        float scrollExtent = mParent.computeVerticalScrollExtent();
        float newScroll = mParent.computeVerticalScrollOffset();

        scrollExtent = computeScrollExtent(scrollExtent, maxScroll);
        if (scrollExtent < 0f) {
            return;
        }

        // Make changes to the VerticalScrollRange happen gradually
        resizeGradually(maxScroll, newScroll);
        maxScroll -= mMaxScrollDiff;
        newScroll -= mCurrentScrollDiff;

        applyThumbColor(alpha);

        float sweepAngle = computeSweepAngle(scrollExtent, maxScroll);
        float startAngle =
                computeStartAngle(Math.max(0, newScroll), sweepAngle, maxScroll, scrollExtent);

        updateBounds(bounds);

        mDrawToLeft = drawToLeft;
        drawRoundScrollbars(canvas, startAngle, sweepAngle, alpha);
    }

    private void drawRoundScrollbars(
            Canvas canvas, float startAngle, float sweepAngle, float alpha) {
        if (mUseRefactoredRoundScrollbar) {
            draw(canvas, startAngle, sweepAngle, alpha);
        } else {
            applyTrackColor(alpha);
            drawArc(canvas, -SCROLLBAR_ANGLE_RANGE / 2f, SCROLLBAR_ANGLE_RANGE, mTrackPaint);
            drawArc(canvas, startAngle, sweepAngle, mThumbPaint);
        }
    }

    /** Returns true if horizontal bounds are updated */
    private void updateBounds(Rect bounds) {
        mRect.set(
                bounds.left + mInset,
                bounds.top + mInset,
                bounds.right - mInset,
                bounds.bottom - mInset);
        mThumbStrokeWidthAsDegrees =
                getVertexAngle((mRect.right - mRect.left) / 2f, mThumbPaint.getStrokeWidth() / 2f);
    }

    private float computeSweepAngle(float scrollExtent, float maxScroll) {
        // Normalize the sweep angle for the scroll bar.
        float sweepAngle = (scrollExtent / maxScroll) * SCROLLBAR_ANGLE_RANGE;
        return clamp(sweepAngle, MIN_SCROLLBAR_ANGLE_SWIPE, MAX_SCROLLBAR_ANGLE_SWIPE);
    }

    private float computeStartAngle(
            float currentScroll, float sweepAngle, float maxScroll, float scrollExtent) {
        // Normalize the start angle so that it falls on the track.
        float startAngle =
                (currentScroll * (SCROLLBAR_ANGLE_RANGE - sweepAngle)) / (maxScroll - scrollExtent)
                        - SCROLLBAR_ANGLE_RANGE / 2f;
        return clamp(
                startAngle, -SCROLLBAR_ANGLE_RANGE / 2f, SCROLLBAR_ANGLE_RANGE / 2f - sweepAngle);
    }

    void getRoundVerticalScrollBarBounds(Rect bounds) {
        float padding = dpToPx(OUTER_PADDING_DP);
        final int width = mParent.mRight - mParent.mLeft;
        final int height = mParent.mBottom - mParent.mTop;
        bounds.left = mParent.mScrollX + (int) padding;
        bounds.top = mParent.mScrollY + (int) padding;
        bounds.right = mParent.mScrollX + width - (int) padding;
        bounds.bottom = mParent.mScrollY + height - (int) padding;
    }

    private static float clamp(float val, float min, float max) {
        if (val < min) {
            return min;
        } else {
            return Math.min(val, max);
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int alphaByte = (int) (Color.alpha(color) * alpha);
        return Color.argb(alphaByte, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void applyThumbColor(float alpha) {
        int color = applyAlpha(DEFAULT_THUMB_COLOR, alpha);
        if (mThumbPaint.getColor() != color) {
            mThumbPaint.setColor(color);
        }
    }

    private void applyTrackColor(float alpha) {
        int color = applyAlpha(DEFAULT_TRACK_COLOR, alpha);
        if (mTrackPaint.getColor() != color) {
            mTrackPaint.setColor(color);
        }
    }

    private float dpToPx(float dp) {
        return dp * ((float) mParent.getContext().getResources().getDisplayMetrics().densityDpi)
                / DisplayMetrics.DENSITY_DEFAULT;
    }

    private static float getVertexAngle(float edge, float base) {
        float edgeSquare = edge * edge * 2;
        float baseSquare = base * base;
        float gapInRadians = acos(((edgeSquare - baseSquare) / edgeSquare));
        return (float) Math.toDegrees(gapInRadians);
    }

    private static float getKiteEdge(float knownEdge, float angleBetweenKnownEdgesInDegrees) {
        return (float) (2 * knownEdge * sin(Math.toRadians(angleBetweenKnownEdgesInDegrees / 2)));
    }

    private void draw(Canvas canvas, float thumbStartAngle, float thumbSweepAngle, float alpha) {
        // Draws the top arc
        drawTrack(
                canvas,
                // The highest point of the top track on a vertical scale. Here the thumb width is
                // reduced to account for the arc formed by ROUND stroke style
                -SCROLLBAR_ANGLE_RANGE / 2f - mThumbStrokeWidthAsDegrees,
                // The lowest point of the top track on a vertical scale. Here the thumb width is
                // reduced twice to (a) account for the arc formed by ROUND stroke style (b) gap
                // between thumb and top track
                thumbStartAngle - mThumbStrokeWidthAsDegrees * 2,
                alpha);
        // Draws the thumb
        drawArc(canvas, thumbStartAngle, thumbSweepAngle, mThumbPaint);
        // Draws the bottom arc
        drawTrack(
                canvas,
                // The highest point of the bottom track on a vertical scale. Here the thumb width
                // is added twice to (a) account for the arc formed by ROUND stroke style (b) gap
                // between thumb and bottom track
                (thumbStartAngle + thumbSweepAngle) + mThumbStrokeWidthAsDegrees * 2,
                // The lowest point of the top track on a vertical scale. Here the thumb width is
                // added to account for the arc formed by ROUND stroke style
                SCROLLBAR_ANGLE_RANGE / 2f + mThumbStrokeWidthAsDegrees,
                alpha);
    }

    private void drawTrack(Canvas canvas, float beginAngle, float endAngle, float alpha) {
        // Angular distance between end and begin
        float angleBetweenEndAndBegin = endAngle - beginAngle;
        // The sweep angle for the track is the angular distance between end and begin less the
        // thumb width twice to account for top and bottom arc formed by the ROUND stroke style
        float sweepAngle = angleBetweenEndAndBegin - 2 * mThumbStrokeWidthAsDegrees;

        float startAngle = -1f;
        float strokeWidth = -1f;
        if (sweepAngle > 0f) {
            // The angle is greater than 0 which means a normal arc should be drawn with stroke
            // width same as the thumb. The ROUND stroke style will cover the top/bottom arc of the
            // track
            startAngle = beginAngle + mThumbStrokeWidthAsDegrees;
            strokeWidth = mThumbPaint.getStrokeWidth();
        } else if (Math.abs(sweepAngle) < 2 * mThumbStrokeWidthAsDegrees) {
            // The sweep angle is less than 0 but is still relevant in creating a circle for the
            // top/bottom track. The start angle is adjusted to account for being the mid point of
            // begin / end angle.
            startAngle = beginAngle + angleBetweenEndAndBegin / 2;
            // The radius of this circle forms a kite with the radius of the arc drawn for the rect
            // with the given angular difference between the arc radius which is used to compute the
            // new stroke width.
            strokeWidth = getKiteEdge(((mRect.right - mRect.left) / 2), angleBetweenEndAndBegin);
            // The opacity is decreased proportionally, if the stroke width of the track is 50% or
            // less that that of the thumb
            alpha = alpha * Math.min(1f, 2 * strokeWidth / mThumbPaint.getStrokeWidth());
            // As we desire a circle to be drawn, the sweep angle is set to a minimal value
            sweepAngle = Float.MIN_NORMAL;
        } else {
            return;
        }

        applyTrackColor(alpha);
        mTrackPaint.setStrokeWidth(strokeWidth);
        drawArc(canvas, startAngle, sweepAngle, mTrackPaint);
    }

    private void drawArc(Canvas canvas, float startAngle, float sweepAngle, Paint paint) {
        if (mDrawToLeft) {
            canvas.drawArc(mRect, /* startAngle= */ 180 - startAngle, -sweepAngle, false, paint);
        } else {
            canvas.drawArc(mRect, startAngle, sweepAngle, /* useCenter= */ false, paint);
        }
    }
}
