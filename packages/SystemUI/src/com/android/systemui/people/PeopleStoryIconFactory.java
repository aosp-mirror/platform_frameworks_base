/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.IconDrawableFactory;
import android.util.Log;

import com.android.settingslib.Utils;
import com.android.systemui.R;

class PeopleStoryIconFactory implements AutoCloseable {

    private static final int PADDING = 2;
    private static final int RING_WIDTH = 2;
    private static final int MAX_BADGE_SIZE = 40;

    final PackageManager mPackageManager;
    final IconDrawableFactory mIconDrawableFactory;
    private int mImportantConversationColor;
    private int mAccentColor;
    private float mDensity;
    private float mIconSize;
    private Context mContext;

    private final int mIconBitmapSize;

    PeopleStoryIconFactory(Context context, PackageManager pm,
            IconDrawableFactory iconDrawableFactory, int iconSizeDp) {
        context.setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        mIconBitmapSize = (int) (iconSizeDp * context.getResources().getDisplayMetrics().density);
        mDensity = context.getResources().getDisplayMetrics().density;
        mIconSize = mDensity * iconSizeDp;
        mPackageManager = pm;
        mIconDrawableFactory = iconDrawableFactory;
        mImportantConversationColor = context.getColor(R.color.important_conversation);
        mAccentColor = Utils.getColorAttr(context,
                com.android.internal.R.attr.colorAccentPrimary).getDefaultColor();
        mContext = context;
    }


    /**
     * Gets the {@link Drawable} that represents the app icon, badged with the work profile icon
     * if appropriate.
     */
    private Drawable getAppBadge(String packageName, int userId) {
        Drawable badge = null;
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            badge = Utils.getBadgedIcon(mContext, appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            badge = mPackageManager.getDefaultActivityIcon();
        }
        return badge;
    }

    /**
     * Returns a {@link Drawable} for the entire conversation. The shortcut icon will be badged
     * with the launcher icon of the app specified by packageName.
     */
    public Drawable getPeopleTileDrawable(Drawable headDrawable, String packageName, int userId,
            boolean important, boolean newStory) {
        return new PeopleStoryIconDrawable(headDrawable, getAppBadge(packageName, userId),
                mIconBitmapSize, mImportantConversationColor, important, mIconSize, mDensity,
                mAccentColor, newStory);
    }

    /**
     * Custom drawable which overlays a badge drawable on a head icon (conversation/person avatar),
     * with decorations indicating Important conversations and having a New Story.
     */
    public static class PeopleStoryIconDrawable extends Drawable {
        private float mFullIconSize;
        private Drawable mAvatar;
        private Drawable mBadgeIcon;
        private int mIconSize;
        private Paint mPriorityRingPaint;
        private boolean mShowImportantRing;
        private boolean mShowStoryRing;
        private Paint mStoryPaint;
        private float mDensity;

        PeopleStoryIconDrawable(Drawable avatar,
                Drawable badgeIcon,
                int iconSize,
                @ColorInt int ringColor,
                boolean showImportantRing, float fullIconSize, float density,
                @ColorInt int accentColor, boolean showStoryRing) {
            mAvatar = avatar;
            mBadgeIcon = badgeIcon;
            mIconSize = iconSize;
            mShowImportantRing = showImportantRing;
            mPriorityRingPaint = new Paint();
            mPriorityRingPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mPriorityRingPaint.setColor(ringColor);
            mShowStoryRing = showStoryRing;
            mStoryPaint = new Paint();
            mStoryPaint.setStyle(Paint.Style.STROKE);
            mStoryPaint.setColor(accentColor);
            mFullIconSize = fullIconSize;
            mDensity = density;
        }

        @Override
        public int getIntrinsicWidth() {
            return mIconSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mIconSize;
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect bounds = getBounds();
            final int minBound = Math.min(bounds.height(), bounds.width());
            // Scale head icon and app icon to our canvas.
            float scale = minBound / mFullIconSize;

            int paddingInDp = (int) (PADDING * mDensity);
            int ringStrokeWidth = (int) (RING_WIDTH * mDensity);
            mPriorityRingPaint.setStrokeWidth(ringStrokeWidth);
            mStoryPaint.setStrokeWidth(ringStrokeWidth);

            int scaledFullIconSize = (int) (mFullIconSize * scale);
            int avatarSize = scaledFullIconSize - (paddingInDp * 2);
            if (mAvatar != null) {
                int leftAndTopPadding = paddingInDp;
                int rightAndBottomPadding = avatarSize + paddingInDp;
                if (mShowStoryRing) {
                    int headCenter = scaledFullIconSize / 2;
                    canvas.drawCircle(headCenter, headCenter,
                            getRadius(avatarSize, ringStrokeWidth),
                            mStoryPaint);
                    leftAndTopPadding += (ringStrokeWidth + paddingInDp);
                    rightAndBottomPadding -= (ringStrokeWidth + paddingInDp);
                }
                mAvatar.setBounds(leftAndTopPadding,
                        leftAndTopPadding,
                        rightAndBottomPadding,
                        rightAndBottomPadding);
                mAvatar.draw(canvas);
            } else {
                Log.w("PeopleStoryIconFactory", "Null avatar icon");
            }

            // Determine badge size from either the size relative to the head icon, or max size.
            int maxBadgeSize = (int) (MAX_BADGE_SIZE * mDensity);
            int badgeSizeRelativeToHead = (int) (avatarSize / 2.4);
            int badgeSize = Math.min(maxBadgeSize, badgeSizeRelativeToHead);
            if (mBadgeIcon != null) {
                int leftAndTopPadding = scaledFullIconSize - badgeSize;
                int rightAndBottomPadding = scaledFullIconSize;
                if (mShowImportantRing) {
                    int badgeCenter = leftAndTopPadding + (badgeSize / 2);
                    canvas.drawCircle(badgeCenter, badgeCenter,
                            getRadius(badgeSize, ringStrokeWidth),
                            mPriorityRingPaint);
                    leftAndTopPadding += ringStrokeWidth;
                    rightAndBottomPadding -= ringStrokeWidth;
                }
                mBadgeIcon.setBounds(
                        leftAndTopPadding,
                        leftAndTopPadding,
                        rightAndBottomPadding,
                        rightAndBottomPadding);
                mBadgeIcon.draw(canvas);
            } else {
                Log.w("PeopleStoryIconFactory", "Null badge icon");
            }
        }

        private int getRadius(int circleWidth, int circleStrokeWidth) {
            return (circleWidth - circleStrokeWidth) / 2;
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
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public void close() {
    }
}