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
package android.webkit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for storing a (user,PackageInfo) mapping.
 * @hide
 */
public class UserPackage {
    private final UserInfo mUserInfo;
    private final PackageInfo mPackageInfo;

    public static final int MINIMUM_SUPPORTED_SDK = Build.VERSION_CODES.TIRAMISU;

    public UserPackage(UserInfo user, PackageInfo packageInfo) {
        this.mUserInfo = user;
        this.mPackageInfo = packageInfo;
    }

    /**
     * Returns a list of (User,PackageInfo) pairs corresponding to the PackageInfos for all
     * device users for the package named {@param packageName}.
     */
    public static List<UserPackage> getPackageInfosAllUsers(Context context,
            String packageName, int packageFlags) {
        List<UserInfo> users = getAllUsers(context);
        List<UserPackage> userPackages = new ArrayList<UserPackage>(users.size());
        for (UserInfo user : users) {
            PackageInfo packageInfo = null;
            try {
                packageInfo = context.getPackageManager().getPackageInfoAsUser(
                        packageName, packageFlags, user.id);
            } catch (NameNotFoundException e) {
            }
            userPackages.add(new UserPackage(user, packageInfo));
        }
        return userPackages;
    }

    /**
     * Returns whether the given package is enabled.
     * This state can be changed by the user from Settings->Apps
     */
    public boolean isEnabledPackage() {
        if (mPackageInfo == null) return false;
        return mPackageInfo.applicationInfo.enabled;
    }

    /**
     * Return {@code true} if the package is installed and not hidden
     */
    public boolean isInstalledPackage() {
        if (mPackageInfo == null) return false;
        return (((mPackageInfo.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0)
            && ((mPackageInfo.applicationInfo.privateFlags
                        & ApplicationInfo.PRIVATE_FLAG_HIDDEN) == 0));
    }

    /**
     * Returns whether the package represented by {@param packageInfo} targets a sdk version
     * supported by the current framework version.
     */
    public static boolean hasCorrectTargetSdkVersion(PackageInfo packageInfo) {
        return packageInfo.applicationInfo.targetSdkVersion >= MINIMUM_SUPPORTED_SDK;
    }

    public UserInfo getUserInfo() {
        return mUserInfo;
    }

    public PackageInfo getPackageInfo() {
        return mPackageInfo;
    }


    private static List<UserInfo> getAllUsers(Context context) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return userManager.getUsers();
    }

}
