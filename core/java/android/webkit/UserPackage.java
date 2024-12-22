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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for storing a (user,PackageInfo) mapping.
 * @hide
 */
public class UserPackage {
    private final UserHandle mUser;
    private final PackageInfo mPackageInfo;

    public static final int MINIMUM_SUPPORTED_SDK = Build.VERSION_CODES.TIRAMISU;

    public UserPackage(@NonNull UserHandle user, @Nullable PackageInfo packageInfo) {
        mUser = user;
        mPackageInfo = packageInfo;
    }

    /**
     * Returns a list of (User,PackageInfo) pairs corresponding to the PackageInfos for all
     * device users for the package named {@param packageName}.
     */
    public static @NonNull List<UserPackage> getPackageInfosAllUsers(@NonNull Context context,
            @NonNull String packageName, int packageFlags) {
        UserManager userManager = context.getSystemService(UserManager.class);
        List<UserHandle> users = userManager.getUserHandles(false);
        List<UserPackage> userPackages = new ArrayList<UserPackage>(users.size());
        for (UserHandle user : users) {
            PackageManager pm = context.createContextAsUser(user, 0).getPackageManager();
            PackageInfo packageInfo = null;
            try {
                packageInfo = pm.getPackageInfo(packageName, packageFlags);
            } catch (PackageManager.NameNotFoundException e) {
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

    public UserHandle getUser() {
        return mUser;
    }

    public PackageInfo getPackageInfo() {
        return mPackageInfo;
    }
}
