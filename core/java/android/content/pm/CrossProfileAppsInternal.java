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

package android.content.pm;

import android.annotation.UserIdInt;
import android.app.AppOpsManager.Mode;
import android.os.UserHandle;

import java.util.List;

/**
 * Exposes internal methods from {@link com.android.server.pm.CrossProfileAppsServiceImpl} to other
 * system server classes.
 *
 * @hide Only for use within the system server.
 */
public abstract class CrossProfileAppsInternal {
    /**
     * Returns whether the package has the necessary permissions to communicate cross-profile.
     *
     * <p>This means having at least one of these conditions:
     * <ul>
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS_FULL} granted.
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS} granted.
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_PROFILES} granted, or the corresponding
     *     AppOps {@code android:interact_across_profiles} is set to "allow".
     * </ul>
     */
    public abstract boolean verifyPackageHasInteractAcrossProfilePermission(String packageName,
            @UserIdInt int userId) throws PackageManager.NameNotFoundException;

    /**
     * Returns whether the package has the necessary permissions to communicate cross-profile.
     *
     * <p>This means having at least one of these conditions:
     * <ul>
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS_FULL} granted.
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_USERS} granted.
     *     <li>{@code Manifest.permission.INTERACT_ACROSS_PROFILES} granted, or the corresponding
     *     AppOps {@code android:interact_across_profiles} is set to "allow".
     * </ul>
     */
    public abstract boolean verifyUidHasInteractAcrossProfilePermission(String packageName,
            int uid);

    /**
     * Returns the list of target user profiles for the given package on the given user. See {@link
     * CrossProfileApps#getTargetUserProfiles()}.
     */
    public abstract List<UserHandle> getTargetUserProfiles(
            String packageName, @UserIdInt int userId);

    /**
     * Sets the app-op for {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES} that is
     * configurable by users in Settings. This configures it for the profile group of the given
     * user.
     *
     * @see CrossProfileApps#setInteractAcrossProfilesAppOp(String, int)
     */
    public abstract void setInteractAcrossProfilesAppOp(
            String packageName, @Mode int newMode, @UserIdInt int userId);
}
