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

package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * A representation of an activity that can belong to this user or a managed
 * profile associated with this user. It can be used to query the label, icon
 * and badged icon for the activity.
 */
public class LauncherActivityInfo {
    private static final String TAG = "LauncherActivityInfo";

    private final PackageManager mPm;

    private ActivityInfo mActivityInfo;
    private ComponentName mComponentName;
    private ResolveInfo mResolveInfo;
    private UserHandle mUser;
    private long mFirstInstallTime;

    /**
     * Create a launchable activity object for a given ResolveInfo and user.
     *
     * @param context The context for fetching resources.
     * @param info ResolveInfo from which to create the LauncherActivityInfo.
     * @param user The UserHandle of the profile to which this activity belongs.
     */
    LauncherActivityInfo(Context context, ResolveInfo info, UserHandle user,
            long firstInstallTime) {
        this(context);
        mResolveInfo = info;
        mActivityInfo = info.activityInfo;
        mComponentName = LauncherApps.getComponentName(info);
        mUser = user;
        mFirstInstallTime = firstInstallTime;
    }

    LauncherActivityInfo(Context context) {
        mPm = context.getPackageManager();
    }

    /**
     * Returns the component name of this activity.
     *
     * @return ComponentName of the activity
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the user handle of the user profile that this activity belongs to. In order to
     * persist the identity of the profile, do not store the UserHandle. Instead retrieve its
     * serial number from UserManager. You can convert the serial number back to a UserHandle
     * for later use.
     *
     * @see UserManager#getSerialNumberForUser(UserHandle)
     * @see UserManager#getUserForSerialNumber(long)
     *
     * @return The UserHandle of the profile.
     */
    public UserHandle getUser() {
        return mUser;
    }

    /**
     * Retrieves the label for the activity.
     *
     * @return The label for the activity.
     */
    public CharSequence getLabel() {
        return mResolveInfo.loadLabel(mPm);
    }

    /**
     * Returns the icon for this activity, without any badging for the profile.
     * @param density The preferred density of the icon, zero for default density. Use
     * density DPI values from {@link DisplayMetrics}.
     * @see #getBadgedIcon(int)
     * @see DisplayMetrics
     * @return The drawable associated with the activity.
     */
    public Drawable getIcon(int density) {
        final int iconRes = mResolveInfo.getIconResource();
        Drawable icon = getDrawableForDensity(iconRes, density);
        // Get the default density icon
        if (icon == null) {
            icon = mResolveInfo.loadIcon(mPm);
        }
        return icon;
    }

    /**
     * Returns the icon for this activity, without any badging for the profile.
     * This function can get the icon no matter the icon needs to be badged or not.
     * @param density The preferred density of the icon, zero for default density. Use
     * density DPI values from {@link DisplayMetrics}.
     * @see #getBadgedIcon(int)
     * @see DisplayMetrics
     * @return The drawable associated with the activity.
     */
    private Drawable getOriginalIcon(int density) {
        final int iconRes = mResolveInfo.getIconResourceInternal();
        Drawable icon = getDrawableForDensity(iconRes, density);
        // Get the default density icon
        if (icon == null) {
            icon = mResolveInfo.loadIcon(mPm);
        }
        return icon;
    }

    /**
     * Returns the drawable for this activity, without any badging for the profile.
     * @param resource id of the drawable.
     * @param density The preferred density of the icon, zero for default density. Use
     * density DPI values from {@link DisplayMetrics}.
     * @see DisplayMetrics
     * @return The drawable associated with the resource id.
     */
    private Drawable getDrawableForDensity(int iconRes, int density) {
        // Get the preferred density icon from the app's resources
        if (density != 0 && iconRes != 0) {
            try {
                final Resources resources
                        = mPm.getResourcesForApplication(mActivityInfo.applicationInfo);
                return resources.getDrawableForDensity(iconRes, density);
            } catch (NameNotFoundException | Resources.NotFoundException exc) {
            }
        }
        return null;
    }

    /**
     * Returns the application flags from the ApplicationInfo of the activity.
     *
     * @return Application flags
     * @hide remove before shipping
     */
    public int getApplicationFlags() {
        return mActivityInfo.applicationInfo.flags;
    }

    /**
     * Returns the application info for the appliction this activity belongs to.
     * @return
     */
    public ApplicationInfo getApplicationInfo() {
        return mActivityInfo.applicationInfo;
    }

    /**
     * Returns the time at which the package was first installed.
     *
     * @return The time of installation of the package, in milliseconds.
     */
    public long getFirstInstallTime() {
        return mFirstInstallTime;
    }

    /**
     * Returns the name for the acitivty from  android:name in the manifest.
     * @return the name from android:name for the acitivity.
     */
    public String getName() {
        return mActivityInfo.name;
    }

    /**
     * Returns the activity icon with badging appropriate for the profile.
     * @param density Optional density for the icon, or 0 to use the default density. Use
     * {@link DisplayMetrics} for DPI values.
     * @see DisplayMetrics
     * @return A badged icon for the activity.
     */
    public Drawable getBadgedIcon(int density) {
        Drawable originalIcon = getOriginalIcon(density);

        if (originalIcon instanceof BitmapDrawable) {
            return mPm.getUserBadgedIcon(originalIcon, mUser);
        } else {
            Log.e(TAG, "Unable to create badged icon for " + mActivityInfo);
        }
        return originalIcon;
    }
}
