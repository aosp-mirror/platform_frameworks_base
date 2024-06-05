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

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;

/**
 * Helper class for drawing round scroll bars on round Wear devices.
 */
class RoundScrollbarRenderer {
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
    private final int mMaskThickness;

    private float mPreviousMaxScroll = 0;
    private float mMaxScrollDiff = 0;
    private float mPreviousCurrentScroll = 0;
    private float mCurrentScrollDiff = 0;

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
        mMaskThickness = parent.getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.circular_display_mask_thickness);
    }

    public void drawRoundScrollbars(Canvas canvas, float alpha, Rect bounds, boolean drawToLeft) {
        if (alpha == 0) {
            return;
        }
        // Get information about the current scroll state of the parent view.
        float maxScroll = mParent.computeVerticalScrollRange();
        float scrollExtent = mParent.computeVerticalScrollExtent();
        float newScroll = mParent.computeVerticalScrollOffset();

        if (scrollExtent <= 0) {
            if (!mParent.canScrollVertically(1) && !mParent.canScrollVertically(-1)) {
                return;
            } else {
                scrollExtent = 0;
            }
        } else if (maxScroll <= scrollExtent) {
            return;
        }

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

            maxScroll -= mMaxScrollDiff;
            newScroll -= mCurrentScrollDiff;
        } else {
            mMaxScrollDiff = 0;
            mCurrentScrollDiff = 0;
        }

        float currentScroll = Math.max(0, newScroll);
        float linearThumbLength = scrollExtent;
        float thumbWidth = dpToPx(THUMB_WIDTH_DP);
        mThumbPaint.setStrokeWidth(thumbWidth);
        mTrackPaint.setStrokeWidth(thumbWidth);

        setThumbColor(applyAlpha(DEFAULT_THUMB_COLOR, alpha));
        setTrackColor(applyAlpha(DEFAULT_TRACK_COLOR, alpha));

        // Normalize the sweep angle for the scroll bar.
        float sweepAngle = (linearThumbLength / maxScroll) * SCROLLBAR_ANGLE_RANGE;
        sweepAngle = clamp(sweepAngle, MIN_SCROLLBAR_ANGLE_SWIPE, MAX_SCROLLBAR_ANGLE_SWIPE);
        // Normalize the start angle so that it falls on the track.
        float startAngle = (currentScroll * (SCROLLBAR_ANGLE_RANGE - sweepAngle))
                / (maxScroll - linearThumbLength) - SCROLLBAR_ANGLE_RANGE / 2f;
        startAngle = clamp(startAngle, -SCROLLBAR_ANGLE_RANGE / 2f,
                SCROLLBAR_ANGLE_RANGE / 2f - sweepAngle);

        // Draw the track and the thumb.
        float inset = thumbWidth / 2 + mMaskThickness;
        mRect.set(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset);

        if (drawToLeft) {
            canvas.drawArc(mRect, 180 + SCROLLBAR_ANGLE_RANGE / 2f, -SCROLLBAR_ANGLE_RANGE, false,
                    mTrackPaint);
            canvas.drawArc(mRect, 180 - startAngle, -sweepAngle, false, mThumbPaint);
        } else {
            canvas.drawArc(mRect, -SCROLLBAR_ANGLE_RANGE / 2f, SCROLLBAR_ANGLE_RANGE, false,
                    mTrackPaint);
            canvas.drawArc(mRect, startAngle, sweepAngle, false, mThumbPaint);
        }
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
        } else if (val > max) {
            return max;
        } else {
            return val;
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int alphaByte = (int) (Color.alpha(color) * alpha);
        return Color.argb(alphaByte, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void setThumbColor(int thumbColor) {
        if (mThumbPaint.getColor() != thumbColor) {
            mThumbPaint.setColor(thumbColor);
        }
    }

    private void setTrackColor(int trackColor) {
        if (mTrackPaint.getColor() != trackColor) {
            mTrackPaint.setColor(trackColor);
        }
    }

    private float dpToPx(float dp) {
        return dp * ((float) mParent.getContext().getResources().getDisplayMetrics().densityDpi)
                / DisplayMetrics.DENSITY_DEFAULT;
    }
}
