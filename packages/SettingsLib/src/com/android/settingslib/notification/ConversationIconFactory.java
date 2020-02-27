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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.IconDrawableFactory;

import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.ShadowGenerator;

/**
 * Factory for creating normalized conversation icons.
 * We are not using Launcher's IconFactory because conversation rendering only runs on the UI
 * thread, so there is no need to manage a pool across multiple threads.
 */
public class ConversationIconFactory extends BaseIconFactory {

    final LauncherApps mLauncherApps;
    final PackageManager mPackageManager;
    final IconDrawableFactory mIconDrawableFactory;

    public ConversationIconFactory(Context context, LauncherApps la, PackageManager pm,
            IconDrawableFactory iconDrawableFactory, int iconSizePx) {
        super(context, context.getResources().getConfiguration().densityDpi,
                iconSizePx);
        mLauncherApps = la;
        mPackageManager = pm;
        mIconDrawableFactory = iconDrawableFactory;
    }

    private int getBadgeSize() {
        return mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.icons.R.dimen.profile_badge_size);
    }
    /**
     * Returns the conversation info drawable
     */
    private Drawable getConversationDrawable(ShortcutInfo shortcutInfo) {
        return mLauncherApps.getShortcutIconDrawable(shortcutInfo, mFillResIconDpi);
    }

    /**
     * Get the {@link Drawable} that represents the app icon
     */
    private Drawable getBadgedIcon(String packageName, int userId) {
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            return mIconDrawableFactory.getBadgedIcon(appInfo, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return mPackageManager.getDefaultActivityIcon();
        }
    }

    /**
     * Turns a Drawable into a Bitmap
     */
    BitmapInfo toBitmap(Drawable userBadgedAppIcon) {
        Bitmap bitmap = createIconBitmap(
                userBadgedAppIcon, 1f, getBadgeSize());

        Canvas c = new Canvas();
        ShadowGenerator shadowGenerator = new ShadowGenerator(getBadgeSize());
        c.setBitmap(bitmap);
        shadowGenerator.recreateIcon(Bitmap.createBitmap(bitmap), c);
        return createIconBitmap(bitmap);
    }

    /**
     * Returns a {@link BitmapInfo} for the entire conversation icon including the badge.
     */
    public Bitmap getConversationBitmap(ShortcutInfo info, String packageName, int uid) {
        return getConversationBitmap(getConversationDrawable(info), packageName, uid);
    }

    /**
     * Returns a {@link BitmapInfo} for the entire conversation icon including the badge.
     */
    public Bitmap getConversationBitmap(Drawable baseIcon, String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        Drawable badge = getBadgedIcon(packageName, userId);
        BitmapInfo iconInfo = createBadgedIconBitmap(baseIcon,
                UserHandle.of(userId),
                true /* shrinkNonAdaptiveIcons */);

        badgeWithDrawable(iconInfo.icon,
                new BitmapDrawable(mContext.getResources(), toBitmap(badge).icon));
        return iconInfo.icon;
    }
}
