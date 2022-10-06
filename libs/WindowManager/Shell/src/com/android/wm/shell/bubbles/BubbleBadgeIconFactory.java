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
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.BitmapInfo;
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
        if (userBadgedAppIcon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) userBadgedAppIcon;
            userBadgedAppIcon = new CircularAdaptiveIcon(ad.getBackground(), ad.getForeground());
        }
        if (isImportantConversation) {
            userBadgedAppIcon = new CircularRingDrawable(userBadgedAppIcon);
        }
        Bitmap userBadgedBitmap = createIconBitmap(
                userBadgedAppIcon, 1, BITMAP_GENERATION_MODE_WITH_SHADOW);
        return createIconBitmap(userBadgedBitmap);
    }

    private class CircularRingDrawable extends CircularAdaptiveIcon {

        final int mImportantConversationColor;
        final Rect mTempBounds = new Rect();

        final Drawable mDr;

        CircularRingDrawable(Drawable dr) {
            super(null, null);
            mDr = dr;
            mImportantConversationColor = mContext.getResources().getColor(
                    R.color.important_conversation, null);
        }

        @Override
        public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(getIconMask());
            canvas.drawColor(mImportantConversationColor);
            int ringStrokeWidth = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_stroke_width);
            mTempBounds.set(getBounds());
            mTempBounds.inset(ringStrokeWidth, ringStrokeWidth);
            mDr.setBounds(mTempBounds);
            mDr.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private static class CircularAdaptiveIcon extends AdaptiveIconDrawable {

        final Path mPath = new Path();

        CircularAdaptiveIcon(Drawable bg, Drawable fg) {
            super(bg, fg);
        }

        @Override
        public Path getIconMask() {
            mPath.reset();
            Rect bounds = getBounds();
            mPath.addOval(bounds.left, bounds.top, bounds.right, bounds.bottom, Path.Direction.CW);
            return mPath;
        }

        @Override
        public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(getIconMask());

            canvas.drawColor(Color.BLACK);
            Drawable d;
            if ((d = getBackground()) != null) {
                d.draw(canvas);
            }
            if ((d = getForeground()) != null) {
                d.draw(canvas);
            }
            canvas.restoreToCount(save);
        }
    }
}
