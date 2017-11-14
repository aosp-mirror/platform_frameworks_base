/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.util;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Utility class to load app drawables with appropriate badging.
 *
 * @hide
 */
public class IconDrawableFactory {

    protected final Context mContext;
    protected final PackageManager mPm;
    protected final UserManager mUm;
    protected final LauncherIcons mLauncherIcons;
    protected final boolean mEmbedShadow;

    private IconDrawableFactory(Context context, boolean embedShadow) {
        mContext = context;
        mPm = context.getPackageManager();
        mUm = context.getSystemService(UserManager.class);
        mLauncherIcons = new LauncherIcons(context);
        mEmbedShadow = embedShadow;
    }

    protected boolean needsBadging(ApplicationInfo appInfo, @UserIdInt int userId) {
        return appInfo.isInstantApp() || mUm.isManagedProfile(userId);
    }

    public Drawable getBadgedIcon(ApplicationInfo appInfo) {
        return getBadgedIcon(appInfo, UserHandle.getUserId(appInfo.uid));
    }

    public Drawable getBadgedIcon(ApplicationInfo appInfo, @UserIdInt int userId) {
        return getBadgedIcon(appInfo, appInfo, userId);
    }

    public Drawable getBadgedIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo,
            @UserIdInt int userId) {
        Drawable icon = mPm.loadUnbadgedItemIcon(itemInfo, appInfo);
        if (!mEmbedShadow && !needsBadging(appInfo, userId)) {
            return icon;
        }

        icon = getShadowedIcon(icon);
        if (appInfo.isInstantApp()) {
            int badgeColor = Resources.getSystem().getColor(
                    com.android.internal.R.color.instant_app_badge, null);
            icon = mLauncherIcons.getBadgedDrawable(icon,
                    com.android.internal.R.drawable.ic_instant_icon_badge_bolt,
                    badgeColor);
        }
        if (mUm.isManagedProfile(userId)) {
            icon = mLauncherIcons.getBadgedDrawable(icon,
                    com.android.internal.R.drawable.ic_corp_icon_badge_case,
                    getUserBadgeColor(mUm, userId));
        }
        return icon;
    }

    /**
     * Add shadow to the icon if {@link AdaptiveIconDrawable}
     */
    public Drawable getShadowedIcon(Drawable icon) {
        return mLauncherIcons.wrapIconDrawableWithShadow(icon);
    }

    // Should have enough colors to cope with UserManagerService.getMaxManagedProfiles()
    @VisibleForTesting
    public static final int[] CORP_BADGE_COLORS = new int[] {
            com.android.internal.R.color.profile_badge_1,
            com.android.internal.R.color.profile_badge_2,
            com.android.internal.R.color.profile_badge_3
    };

    public static int getUserBadgeColor(UserManager um, @UserIdInt int userId) {
        int badge = um.getManagedProfileBadge(userId);
        if (badge < 0) {
            badge = 0;
        }
        int resourceId = CORP_BADGE_COLORS[badge % CORP_BADGE_COLORS.length];
        return Resources.getSystem().getColor(resourceId, null);
    }

    public static IconDrawableFactory newInstance(Context context) {
        return new IconDrawableFactory(context, true);
    }

    public static IconDrawableFactory newInstance(Context context, boolean embedShadow) {
        return new IconDrawableFactory(context, embedShadow);
    }
}
