/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.ShadowGenerator;
import com.android.wm.shell.R;

/**
 * Factory for creating app badge icons that are shown on bubbles.
 */
public class BubbleBadgeIconFactory extends BaseIconFactory {

    public BubbleBadgeIconFactory(Context context) {
        super(context, context.getResources().getConfiguration().densityDpi,
                context.getResources().getDimensionPixelSize(R.dimen.bubble_badge_size));
    }

    /**
     * Returns a {@link BitmapInfo} for the app-badge that is shown on top of each bubble. This
     * will include the workprofile indicator on the badge if appropriate.
     */
    BitmapInfo getBadgeBitmap(Drawable userBadgedAppIcon, boolean isImportantConversation) {
        ShadowGenerator shadowGenerator = new ShadowGenerator(mIconBitmapSize);
        Bitmap userBadgedBitmap = createIconBitmap(userBadgedAppIcon, 1f, mIconBitmapSize);

        if (userBadgedAppIcon instanceof AdaptiveIconDrawable) {
            userBadgedBitmap = Bitmap.createScaledBitmap(
                    getCircleBitmap((AdaptiveIconDrawable) userBadgedAppIcon, /* size */
                            userBadgedAppIcon.getIntrinsicWidth()),
                    mIconBitmapSize, mIconBitmapSize, /* filter */ true);
        }

        if (isImportantConversation) {
            final float ringStrokeWidth = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_stroke_width);
            final int importantConversationColor = mContext.getResources().getColor(
                    R.color.important_conversation, null);
            Bitmap badgeAndRing = Bitmap.createBitmap(userBadgedBitmap.getWidth(),
                    userBadgedBitmap.getHeight(), userBadgedBitmap.getConfig());
            Canvas c = new Canvas(badgeAndRing);

            Paint ringPaint = new Paint();
            ringPaint.setStyle(Paint.Style.FILL);
            ringPaint.setColor(importantConversationColor);
            ringPaint.setAntiAlias(true);
            c.drawCircle(c.getWidth() / 2, c.getHeight() / 2, c.getWidth() / 2, ringPaint);

            final int bitmapTop = (int) ringStrokeWidth;
            final int bitmapLeft = (int) ringStrokeWidth;
            final int bitmapWidth = c.getWidth() - 2 * (int) ringStrokeWidth;
            final int bitmapHeight = c.getHeight() - 2 * (int) ringStrokeWidth;

            Bitmap scaledBitmap = Bitmap.createScaledBitmap(userBadgedBitmap, bitmapWidth,
                    bitmapHeight, /* filter */ true);
            c.drawBitmap(scaledBitmap, bitmapTop, bitmapLeft, /* paint */null);

            shadowGenerator.recreateIcon(Bitmap.createBitmap(badgeAndRing), c);
            return createIconBitmap(badgeAndRing);
        } else {
            Canvas c = new Canvas();
            c.setBitmap(userBadgedBitmap);
            shadowGenerator.recreateIcon(Bitmap.createBitmap(userBadgedBitmap), c);
            return createIconBitmap(userBadgedBitmap);
        }
    }

    private Bitmap getCircleBitmap(AdaptiveIconDrawable icon, int size) {
        Drawable foreground = icon.getForeground();
        Drawable background = icon.getBackground();
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas();
        canvas.setBitmap(bitmap);

        // Clip canvas to circle.
        Path circlePath = new Path();
        circlePath.addCircle(/* x */ size / 2f,
                /* y */ size / 2f,
                /* radius */ size / 2f,
                Path.Direction.CW);
        canvas.clipPath(circlePath);

        // Draw background.
        background.setBounds(0, 0, size, size);
        background.draw(canvas);

        // Draw foreground. The foreground and background drawables are derived from adaptive icons
        // Some icon shapes fill more space than others, so adaptive icons are normalized to about
        // the same size. This size is smaller than the original bounds, so we estimate
        // the difference in this offset.
        int offset = size / 5;
        foreground.setBounds(-offset, -offset, size + offset, size + offset);
        foreground.draw(canvas);

        canvas.setBitmap(null);
        return bitmap;
    }
}
