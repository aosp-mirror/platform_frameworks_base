/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.bubbles;

import static android.graphics.Paint.ANTI_ALIAS_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;

// XXX: Mostly opied from launcher code / can we share?
/**
 * Contains parameters necessary to draw a badge for an icon (e.g. the size of the badge).
 */
public class BadgeRenderer {

    private static final String TAG = "BadgeRenderer";

    // The badge sizes are defined as percentages of the app icon size.
    private static final float SIZE_PERCENTAGE = 0.38f;

    // Extra scale down of the dot
    private static final float DOT_SCALE = 0.6f;

    private final float mDotCenterOffset;
    private final float mCircleRadius;
    private final Paint mCirclePaint = new Paint(ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG);

    public BadgeRenderer(int iconSizePx) {
        mDotCenterOffset = SIZE_PERCENTAGE * iconSizePx;
        int size = (int) (DOT_SCALE * mDotCenterOffset);
        mCircleRadius = size / 2f;
    }

    /**
     * Draw a circle in the top right corner of the given bounds.
     *
     * @param color The color (based on the icon) to use for the badge.
     * @param iconBounds The bounds of the icon being badged.
     * @param badgeScale The progress of the animation, from 0 to 1.
     * @param spaceForOffset How much space to offset the badge up and to the left or right.
     * @param onLeft Whether the badge should be draw on left or right side.
     */
    public void draw(Canvas canvas, int color, Rect iconBounds, float badgeScale,
            Point spaceForOffset, boolean onLeft) {
        if (iconBounds == null) {
            Log.e(TAG, "Invalid null argument(s) passed in call to draw.");
            return;
        }
        canvas.save();
        // We draw the badge relative to its center.
        int x = onLeft ? iconBounds.left : iconBounds.right;
        float offset = onLeft ? (mDotCenterOffset / 2) : -(mDotCenterOffset / 2);
        float badgeCenterX = x + offset;
        float badgeCenterY = iconBounds.top + mDotCenterOffset / 2;

        canvas.translate(badgeCenterX + spaceForOffset.x, badgeCenterY - spaceForOffset.y);

        canvas.scale(badgeScale, badgeScale);
        mCirclePaint.setColor(color);
        canvas.drawCircle(0, 0, mCircleRadius, mCirclePaint);
        canvas.restore();
    }
}
