/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.android.systemui.dagger.SysUISingleton;

import java.util.List;

import javax.inject.Inject;

/**
 * Contains useful methods for querying properties of an Activity Intent.
 */
@SysUISingleton
public class ActivityIntentHelper {

    private final Context mContext;

    @Inject
    public ActivityIntentHelper(Context context) {
        // TODO: inject a package manager, not a context.
        mContext = context;
    }

    /**
     * Determines if sending the given intent would result in starting an Intent resolver activity,
     * instead of resolving to a specific component.
     *
     * @param intent the intent
     * @param currentUserId the id for the user to resolve as
     * @return true if the intent would launch a resolver activity
     */
    public boolean wouldLaunchResolverActivity(Intent intent, int currentUserId) {
        ActivityInfo targetActivityInfo = getTargetActivityInfo(intent, currentUserId,
                false /* onlyDirectBootAware */);
        return targetActivityInfo == null;
    }

    /**
     * Returns info about the target Activity of a given intent, or null if the intent does not
     * resolve to a specific component meeting the requirements.
     *
     * @param onlyDirectBootAware a boolean indicating whether the matched activity packages must
     *         be direct boot aware when in direct boot mode if false, all packages are considered
     *         a match even if they are not aware.
     * @return the target activity info of the intent it resolves to a specific package or
     *         {@code null} if it resolved to the resolver activity
     */
    public ActivityInfo getTargetActivityInfo(Intent intent, int currentUserId,
            boolean onlyDirectBootAware) {
        PackageManager packageManager = mContext.getPackageManager();
        int flags = PackageManager.MATCH_DEFAULT_ONLY;
        if (!onlyDirectBootAware) {
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        }
        final List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, flags, currentUserId);
        if (appList.size() == 0) {
            return null;
        }
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                flags | PackageManager.GET_META_DATA, currentUserId);
        if (resolved == null || wouldLaunchResolverActivity(resolved, appList)) {
            return null;
        } else {
            return resolved.activityInfo;
        }
    }

    /**
     * Determines if the given intent resolves to an Activity which is allowed to appear above
     * the lock screen.
     *
     * @param intent the intent to resolve
     * @return true if the launched Activity would appear above the lock screen
     */
    public boolean wouldShowOverLockscreen(Intent intent, int currentUserId) {
        ActivityInfo targetActivityInfo = getTargetActivityInfo(intent,
                currentUserId, false /* onlyDirectBootAware */);
        return targetActivityInfo != null
                && (targetActivityInfo.flags & (ActivityInfo.FLAG_SHOW_WHEN_LOCKED
                | ActivityInfo.FLAG_SHOW_FOR_ALL_USERS)) > 0;
    }

    /**
     * Determines if sending the given intent would result in starting an Intent resolver activity,
     * instead of resolving to a specific component.
     *
     * @param resolved the resolveInfo for the intent as returned by resolveActivityAsUser
     * @param appList a list of resolveInfo as returned by queryIntentActivitiesAsUser
     * @return true if the intent would launch a resolver activity
     */
    public boolean wouldLaunchResolverActivity(ResolveInfo resolved, List<ResolveInfo> appList) {
        // If the list contains the above resolved activity, then it can't be
        // ResolverActivity itself.
        for (int i = 0; i < appList.size(); i++) {
            ResolveInfo tmp = appList.get(i);
            if (tmp.activityInfo.name.equals(resolved.activityInfo.name)
                    && tmp.activityInfo.packageName.equals(resolved.activityInfo.packageName)) {
                return false;
            }
        }
        return true;
    }
}
