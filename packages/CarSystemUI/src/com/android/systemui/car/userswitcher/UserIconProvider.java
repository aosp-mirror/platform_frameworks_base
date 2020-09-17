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

package com.android.systemui.car.userswitcher;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.internal.util.UserIcons;
import com.android.systemui.R;

/**
 * Simple class for providing icons for users.
 */
public class UserIconProvider {
    /**
     * Gets a scaled rounded icon for the given user.  If a user does not have an icon saved, this
     * method will default to a generic icon and update UserManager to use that icon.
     *
     * @param userInfo User for which the icon is requested.
     * @param context Context to use for resources
     * @return {@link RoundedBitmapDrawable} representing the icon for the user.
     */
    public RoundedBitmapDrawable getRoundedUserIcon(UserInfo userInfo, Context context) {
        UserManager userManager = UserManager.get(context);
        Resources res = context.getResources();
        Bitmap icon = userManager.getUserIcon(userInfo.id);

        if (icon == null) {
            icon = assignDefaultIcon(userManager, res, userInfo);
        }

        return createScaledRoundIcon(res, icon);
    }

    /** Returns a scaled, rounded, default icon for the Guest user */
    public RoundedBitmapDrawable getRoundedGuestDefaultIcon(Resources resources) {
        return createScaledRoundIcon(resources, getGuestUserDefaultIcon(resources));
    }

    private RoundedBitmapDrawable createScaledRoundIcon(Resources resources, Bitmap icon) {
        BitmapDrawable scaledIcon = scaleUserIcon(resources, icon);
        RoundedBitmapDrawable circleIcon =
                RoundedBitmapDrawableFactory.create(resources, scaledIcon.getBitmap());
        circleIcon.setCircular(true);
        return circleIcon;
    }

    /**
     * Returns a {@link Drawable} for the given {@code icon} scaled to the appropriate size.
     */
    private static BitmapDrawable scaleUserIcon(Resources res, Bitmap icon) {
        int desiredSize = res.getDimensionPixelSize(R.dimen.car_primary_icon_size);
        Bitmap scaledIcon =
                Bitmap.createScaledBitmap(icon, desiredSize, desiredSize, /*filter=*/ true);
        return new BitmapDrawable(res, scaledIcon);
    }

    /**
     * Assigns a default icon to a user according to the user's id. Handles Guest icon and non-guest
     * user icons.
     *
     * @param userManager {@link UserManager} to set user icon
     * @param resources {@link Resources} to grab icons from
     * @param userInfo User whose avatar is set to default icon.
     * @return Bitmap of the user icon.
     */
    public Bitmap assignDefaultIcon(
            UserManager userManager, Resources resources, UserInfo userInfo) {
        Bitmap bitmap = userInfo.isGuest()
                ? getGuestUserDefaultIcon(resources)
                : getUserDefaultIcon(resources, userInfo.id);
        userManager.setUserIcon(userInfo.id, bitmap);
        return bitmap;
    }

    /**
     * Gets a bitmap representing the user's default avatar.
     *
     * @param resources The resources to pull from
     * @param id The id of the user to get the icon for.  Pass {@link UserHandle#USER_NULL} for
     *           Guest user.
     * @return Default user icon
     */
    private Bitmap getUserDefaultIcon(Resources resources, @UserIdInt int id) {
        return UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(resources, id, /* light= */ false));
    }

    private Bitmap getGuestUserDefaultIcon(Resources resources) {
        return getUserDefaultIcon(resources, UserHandle.USER_NULL);
    }
}
