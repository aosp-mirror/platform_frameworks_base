/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.ColorInt;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import com.android.internal.R;

/**
 * Helper class that generates default user icons.
 */
public class UserIcons {

    private static final int[] USER_ICON_COLORS = {
        R.color.user_icon_1,
        R.color.user_icon_2,
        R.color.user_icon_3,
        R.color.user_icon_4,
        R.color.user_icon_5,
        R.color.user_icon_6,
        R.color.user_icon_7,
        R.color.user_icon_8
    };

    /**
     * Converts a given drawable to a bitmap.
     */
    public static Bitmap convertToBitmap(Drawable icon) {
        return convertToBitmapAtSize(icon, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
    }

    /**
     * Converts a given drawable to a bitmap, with width and height equal to the default icon size.
     */
    public static Bitmap convertToBitmapAtUserIconSize(Resources res, Drawable icon) {
        int size = res.getDimensionPixelSize(R.dimen.user_icon_size);
        return convertToBitmapAtSize(icon, size, size);
    }

    private static Bitmap convertToBitmapAtSize(Drawable icon, int width, int height) {
        if (icon == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        icon.setBounds(0, 0, width, height);
        icon.draw(canvas);
        return bitmap;
    }

    /**
     * Returns a default user icon for the given user.
     *
     * Note that for guest users, you should pass in {@code UserHandle.USER_NULL}.
     *
     * @param resources resources object to fetch user icon / color.
     * @param userId the user id or {@code UserHandle.USER_NULL} for a non-user specific icon
     * @param light whether we want a light icon (suitable for a dark background)
     */
    public static Drawable getDefaultUserIcon(Resources resources, int userId, boolean light) {
        int colorResId = light ? R.color.user_icon_default_white : R.color.user_icon_default_gray;
        if (userId != UserHandle.USER_NULL) {
            // Return colored icon instead
            colorResId = USER_ICON_COLORS[userId % USER_ICON_COLORS.length];
        }
        return getDefaultUserIconInColor(resources, resources.getColor(colorResId, null));
    }

    /**
     * Returns a default user icon in a particular color.
     *
     * @param resources resources object to fetch the user icon
     * @param color the color used for the icon
     */
    public static Drawable getDefaultUserIconInColor(Resources resources, @ColorInt int color) {
        Drawable icon = resources.getDrawable(R.drawable.ic_account_circle, null).mutate();
        icon.setColorFilter(color, Mode.SRC_IN);
        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        return icon;
    }

    /**
     * Returns an array containing colors to be used for default user icons.
     */
    public static int[] getUserIconColors(Resources resources) {
        int[] result = new int[USER_ICON_COLORS.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = resources.getColor(USER_ICON_COLORS[i], null);
        }
        return result;
    }
}
