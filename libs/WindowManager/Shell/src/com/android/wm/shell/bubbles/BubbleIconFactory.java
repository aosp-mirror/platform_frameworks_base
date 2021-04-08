/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.ShadowGenerator;
import com.android.wm.shell.R;

/**
 * Factory for creating normalized bubble icons.
 * We are not using Launcher's IconFactory because bubbles only runs on the UI thread,
 * so there is no need to manage a pool across multiple threads.
 */
public class BubbleIconFactory extends BaseIconFactory {

    private int mBadgeSize;

    protected BubbleIconFactory(Context context) {
        super(context, context.getResources().getConfiguration().densityDpi,
                context.getResources().getDimensionPixelSize(R.dimen.individual_bubble_size));
        mBadgeSize = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.icons.R.dimen.profile_badge_size);
    }

    /**
     * Returns the drawable that the developer has provided to display in the bubble.
     */
    Drawable getBubbleDrawable(@NonNull final Context context,
            @Nullable final ShortcutInfo shortcutInfo, @Nullable final Icon ic) {
        if (shortcutInfo != null) {
            LauncherApps launcherApps =
                    (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            int density = context.getResources().getConfiguration().densityDpi;
            return launcherApps.getShortcutIconDrawable(shortcutInfo, density);
        } else {
            if (ic != null) {
                if (ic.getType() == Icon.TYPE_URI
                        || ic.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
                    context.grantUriPermission(context.getPackageName(),
                            ic.getUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                return ic.loadDrawable(context);
            }
            return null;
        }
    }

    /**
     * Returns a {@link BitmapInfo} for the app-badge that is shown on top of each bubble. This
     * will include the workprofile indicator on the badge if appropriate.
     */
    BitmapInfo getBadgeBitmap(Drawable userBadgedAppIcon, boolean isImportantConversation) {
        ShadowGenerator shadowGenerator = new ShadowGenerator(mBadgeSize);
        Bitmap userBadgedBitmap = createIconBitmap(userBadgedAppIcon, 1f, mBadgeSize);

        if (userBadgedAppIcon instanceof AdaptiveIconDrawable) {
            userBadgedBitmap = Bitmap.createScaledBitmap(
                    getCircleBitmap((AdaptiveIconDrawable) userBadgedAppIcon, /* size */
                            userBadgedAppIcon.getIntrinsicWidth()),
                    mBadgeSize, mBadgeSize, /* filter */ true);
        }

        if (isImportantConversation) {
            final float ringStrokeWidth = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.importance_ring_stroke_width);
            final int importantConversationColor = mContext.getResources().getColor(
                    R.color.important_conversation, null);
            Bitmap badgeAndRing = Bitmap.createBitmap(userBadgedBitmap.getWidth(),
                    userBadgedBitmap.getHeight(), userBadgedBitmap.getConfig());
            Canvas c = new Canvas(badgeAndRing);

            final int bitmapTop = (int) ringStrokeWidth;
            final int bitmapLeft = (int) ringStrokeWidth;
            final int bitmapWidth = c.getWidth() - 2 * (int) ringStrokeWidth;
            final int bitmapHeight = c.getHeight() - 2 * (int) ringStrokeWidth;

            Bitmap scaledBitmap = Bitmap.createScaledBitmap(userBadgedBitmap, bitmapWidth,
                    bitmapHeight, /* filter */ true);
            c.drawBitmap(scaledBitmap, bitmapTop, bitmapLeft, /* paint */null);

            Paint ringPaint = new Paint();
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(importantConversationColor);
            ringPaint.setAntiAlias(true);
            ringPaint.setStrokeWidth(ringStrokeWidth);
            c.drawCircle(c.getWidth() / 2, c.getHeight() / 2, c.getWidth() / 2 - ringStrokeWidth,
                    ringPaint);

            shadowGenerator.recreateIcon(Bitmap.createBitmap(badgeAndRing), c);
            return createIconBitmap(badgeAndRing);
        } else {
            Canvas c = new Canvas();
            c.setBitmap(userBadgedBitmap);
            shadowGenerator.recreateIcon(Bitmap.createBitmap(userBadgedBitmap), c);
            return createIconBitmap(userBadgedBitmap);
        }
    }

    public Bitmap getCircleBitmap(AdaptiveIconDrawable icon, int size) {
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
