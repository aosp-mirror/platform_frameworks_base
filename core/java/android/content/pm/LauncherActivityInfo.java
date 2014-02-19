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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

/**
 * A representation of an activity that can belong to this user or a managed
 * profile associated with this user. It can be used to query the label, icon
 * and badged icon for the activity.
 */
public class LauncherActivityInfo {
    private static final boolean DEBUG = false;
    private final PackageManager mPm;
    private final UserManager mUm;

    private ActivityInfo mActivityInfo;
    private ComponentName mComponentName;
    private UserHandle mUser;
    // TODO: Fetch this value from PM
    private long mFirstInstallTime;

    /**
     * Create a launchable activity object for a given ResolveInfo and user.
     * 
     * @param context The context for fetching resources.
     * @param info ResolveInfo from which to create the LauncherActivityInfo.
     * @param user The UserHandle of the profile to which this activity belongs.
     */
    LauncherActivityInfo(Context context, ResolveInfo info, UserHandle user) {
        this(context);
        this.mActivityInfo = info.activityInfo;
        this.mComponentName = LauncherApps.getComponentName(info);
        this.mUser = user;
    }

    LauncherActivityInfo(Context context) {
        mPm = context.getPackageManager();
        mUm = UserManager.get(context);
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
     * Returns the user handle of the user profile that this activity belongs to.
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
        return mActivityInfo.loadLabel(mPm);
    }

    /**
     * Returns the icon for this activity, without any badging for the profile.
     * @param density The preferred density of the icon, zero for default density.
     * @see #getBadgedIcon(int)
     * @return The drawable associated with the activity
     */
    public Drawable getIcon(int density) {
        // TODO: Use density
        return mActivityInfo.loadIcon(mPm);
    }

    /**
     * Returns the application flags from the ApplicationInfo of the activity.
     * 
     * @return Application flags
     */
    public int getApplicationFlags() {
        return mActivityInfo.applicationInfo.flags;
    }

    /**
     * Returns the time at which the package was first installed.
     * @return The time of installation of the package, in milliseconds.
     */
    public long getFirstInstallTime() {
        return mFirstInstallTime;
    }

    /**
     * Returns the activity icon with badging appropriate for the profile.
     * @param density Optional density for the icon, or 0 to use the default density.
     * @return A badged icon for the activity.
     */
    public Drawable getBadgedIcon(int density) {
        // TODO: Handle density
        if (mUser.equals(android.os.Process.myUserHandle())) {
            return mActivityInfo.loadIcon(mPm);
        }
        Drawable originalIcon = mActivityInfo.loadIcon(mPm);
        if (originalIcon == null) {
            if (DEBUG) {
                Log.w("LauncherActivityInfo", "Couldn't find icon for activity");
            }
            originalIcon = mPm.getDefaultActivityIcon();
        }
        if (originalIcon instanceof BitmapDrawable) {
            return mUm.getBadgedDrawableForUser(
                    originalIcon, mUser);
        }
        return originalIcon;
    }
}
