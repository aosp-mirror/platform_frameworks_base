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

/**
 * Helper class for drawing round scroll bars on round Wear devices.
 */
class RoundScrollbarRenderer {
    // The range of the scrollbar position represented as an angle in degrees.
    private static final int SCROLLBAR_ANGLE_RANGE = 90;
    private static final int MAX_SCROLLBAR_ANGLE_SWIPE = 16;
    private static final int MIN_SCROLLBAR_ANGLE_SWIPE = 6;
    private static final float WIDTH_PERCENTAGE = 0.02f;
    private static final int DEFAULT_THUMB_COLOR = 0xFFE8EAED;
    private static final int DEFAULT_TRACK_COLOR = 0x4CFFFFFF;

    private final Paint mThumbPaint = new Paint();
    private final Paint mTrackPaint = new Paint();
    private final RectF mRect = new RectF();
    private final View mParent;
    private final int mMaskThickness;

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

    public void drawRoundScrollbars(Canvas canvas, float alpha, Rect bounds) {
        if (alpha == 0) {
            return;
        }
        // Get information about the current scroll state of the parent view.
        float maxScroll = mParent.computeVerticalScrollRange();
        float scrollExtent = mParent.computeVerticalScrollExtent();
        if (scrollExtent <= 0 || maxScroll <= scrollExtent) {
            return;
        }
        float currentScroll = Math.max(0, mParent.computeVerticalScrollOffset());
        float linearThumbLength = mParent.computeVerticalScrollExtent();
        float thumbWidth = mParent.getWidth() * WIDTH_PERCENTAGE;
        mThumbPaint.setStrokeWidth(thumbWidth);
        mTrackPaint.setStrokeWidth(thumbWidth);

        setThumbColor(applyAlpha(DEFAULT_THUMB_COLOR, alpha));
        setTrackColor(applyAlpha(DEFAULT_TRACK_COLOR, alpha));

        // Normalize the sweep angle for the scroll bar.
        float sweepAngle = (linearThumbLength / maxScroll) * SCROLLBAR_ANGLE_RANGE;
        sweepAngle = clamp(sweepAngle, MIN_SCROLLBAR_ANGLE_SWIPE, MAX_SCROLLBAR_ANGLE_SWIPE);
        // Normalize the start angle so that it falls on the track.
        float startAngle = (currentScroll * (SCROLLBAR_ANGLE_RANGE - sweepAngle))
                / (maxScroll - linearThumbLength) - SCROLLBAR_ANGLE_RANGE / 2;
        startAngle = clamp(startAngle, -SCROLLBAR_ANGLE_RANGE / 2,
                SCROLLBAR_ANGLE_RANGE / 2 - sweepAngle);

        // Draw the track and the thumb.
        float inset = thumbWidth / 2 + mMaskThickness;
        mRect.set(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset);
        canvas.drawArc(mRect, -SCROLLBAR_ANGLE_RANGE / 2, SCROLLBAR_ANGLE_RANGE, false,
                mTrackPaint);
        canvas.drawArc(mRect, startAngle, sweepAngle, false, mThumbPaint);
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
}
