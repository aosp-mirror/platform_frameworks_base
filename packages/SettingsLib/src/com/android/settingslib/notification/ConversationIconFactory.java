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
package com.android.settingslib.notification;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.IconDrawableFactory;
import android.util.Log;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.settingslib.R;

/**
 * Factory for creating normalized conversation icons.
 * We are not using Launcher's IconFactory because conversation rendering only runs on the UI
 * thread, so there is no need to manage a pool across multiple threads. Launcher's rendering
 * also includes shadows, which are only appropriate on top of wallpaper, not embedded in UI.
 */
public class ConversationIconFactory extends BaseIconFactory {
    // Geometry of the various parts of the design. All values are 1dp on a 48x48dp icon grid.
    // Space is left around the "head" (main avatar) for
    // ........
    // .HHHHHH.
    // .HHHrrrr
    // .HHHrBBr
    // ....rrrr

    private static final float BASE_ICON_SIZE = 48f;
    private static final float RING_STROKE_WIDTH = 2f;
    private static final float HEAD_SIZE = BASE_ICON_SIZE - RING_STROKE_WIDTH * 2 - 2; // 40
    private static final float BADGE_SIZE = HEAD_SIZE * 0.4f; // 16

    final LauncherApps mLauncherApps;
    final PackageManager mPackageManager;
    final IconDrawableFactory mIconDrawableFactory;
    private int mImportantConversationColor;

    public ConversationIconFactory(Context context, LauncherApps la, PackageManager pm,
            IconDrawableFactory iconDrawableFactory, int iconSizePx) {
        super(context, context.getResources().getConfiguration().densityDpi,
                iconSizePx);
        mLauncherApps = la;
        mPackageManager = pm;
        mIconDrawableFactory = iconDrawableFactory;
        mImportantConversationColor = context.getResources().getColor(
                R.color.important_conversation, null);
    }

    /**
     * Returns the conversation info drawable
     */
    private Drawable getBaseIconDrawable(ShortcutInfo shortcutInfo) {
        return mLauncherApps.getShortcutIconDrawable(shortcutInfo, mFillResIconDpi);
    }

    /**
     * Get the {@link Drawable} that represents the app icon, badged with the work profile icon
     * if appropriate.
     */
    private Drawable getAppBadge(String packageName, int userId) {
        Drawable badge = null;
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            badge = mIconDrawableFactory.getBadgedIcon(appInfo, userId);
        } catch (PackageManager.NameNotFoundException e) {
            badge = mPackageManager.getDefaultActivityIcon();
        }
        return badge;
    }

    /**
     * Returns a {@link Drawable} for the entire conversation. The shortcut icon will be badged
     * with the launcher icon of the app specified by packageName.
     */
    public Drawable getConversationDrawable(ShortcutInfo info, String packageName, int uid,
            boolean important) {
        return getConversationDrawable(getBaseIconDrawable(info), packageName, uid, important);
    }

    /**
     * Returns a {@link Drawable} for the entire conversation. The drawable will be badged
     * with the launcher icon of the app specified by packageName.
     */
    public Drawable getConversationDrawable(Drawable baseIcon, String packageName, int uid,
            boolean important) {
        return new ConversationIconDrawable(baseIcon,
                getAppBadge(packageName, UserHandle.getUserId(uid)),
                mIconBitmapSize,
                mImportantConversationColor,
                important);
    }

    /**
     * Custom Drawable that overlays a badge drawable (e.g. notification small icon or app icon) on
     * a base icon (conversation/person avatar), plus decorations indicating conversation
     * importance.
     */
    public static class ConversationIconDrawable extends Drawable {
        private Drawable mBaseIcon;
        private Drawable mBadgeIcon;
        private int mIconSize;
        private Paint mRingPaint;
        private boolean mShowRing;

        public ConversationIconDrawable(Drawable baseIcon,
                Drawable badgeIcon,
                int iconSize,
                @ColorInt int ringColor,
                boolean showImportanceRing) {
            mBaseIcon = baseIcon;
            mBadgeIcon = badgeIcon;
            mIconSize = iconSize;
            mShowRing = showImportanceRing;
            mRingPaint = new Paint();
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setColor(ringColor);
        }

        /**
         * Show or hide the importance ring.
         */
        public void setImportant(boolean important) {
            if (important != mShowRing) {
                mShowRing = important;
                invalidateSelf();
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return mIconSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mIconSize;
        }

        // Similar to badgeWithDrawable, but relying on the bounds of each underlying drawable
        @Override
        public void draw(Canvas canvas) {
            final Rect bounds = getBounds();

            // scale to our internal 48x48 grid
            final float scale = bounds.width() / BASE_ICON_SIZE;
            final int centerX = bounds.centerX();
            final int centerY = bounds.centerX();
            final int ringStrokeWidth = (int) (RING_STROKE_WIDTH * scale);
            final int headSize = (int) (HEAD_SIZE * scale);
            final int badgeSize = (int) (BADGE_SIZE * scale);

            if (mBaseIcon != null) {
                mBaseIcon.setBounds(
                        centerX - headSize / 2,
                        centerY - headSize / 2,
                        centerX + headSize / 2,
                        centerY + headSize / 2);
                mBaseIcon.draw(canvas);
            } else {
                Log.w("ConversationIconFactory", "ConversationIconDrawable has null base icon");
            }
            if (mBadgeIcon != null) {
                mBadgeIcon.setBounds(
                        bounds.right - badgeSize - ringStrokeWidth,
                        bounds.bottom - badgeSize - ringStrokeWidth,
                        bounds.right - ringStrokeWidth,
                        bounds.bottom - ringStrokeWidth);
                mBadgeIcon.draw(canvas);
            } else {
                Log.w("ConversationIconFactory", "ConversationIconDrawable has null badge icon");
            }
            if (mShowRing) {
                mRingPaint.setStrokeWidth(ringStrokeWidth);
                final float radius = badgeSize * 0.5f + ringStrokeWidth * 0.5f; // stroke outside
                final float cx = bounds.right - badgeSize * 0.5f - ringStrokeWidth;
                final float cy = bounds.bottom - badgeSize * 0.5f - ringStrokeWidth;
                canvas.drawCircle(cx, cy, radius, mRingPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            // unimplemented
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // unimplemented
        }

        @Override
        public int getOpacity() {
            return 0;
        }
    }
}
