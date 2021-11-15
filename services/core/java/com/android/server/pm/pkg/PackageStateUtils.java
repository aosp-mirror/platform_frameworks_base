/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.pkg.PackageUserStateUtils;

import com.android.server.pm.parsing.pkg.AndroidPackage;

public class PackageStateUtils {

    public static boolean isMatch(PackageState packageState, int flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return packageState.isSystem();
        }
        return true;
    }

    public static int[] queryInstalledUsers(PackageStateInternal pkgState, int[] users,
            boolean installed) {
        int num = 0;
        for (int user : users) {
            if (pkgState.getUserStateOrDefault(user).isInstalled() == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (pkgState.getUserStateOrDefault(user).isInstalled() == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    public static boolean isEnabledAndMatches(@Nullable PackageStateInternal packageState,
            ComponentInfo componentInfo, int flags, int userId) {
        if (packageState == null) return false;

        final PackageUserState userState = packageState.getUserStateOrDefault(userId);
        return PackageUserStateUtils.isMatch(userState, componentInfo, flags);
    }

    public static boolean isEnabledAndMatches(@Nullable PackageStateInternal packageState,
            @NonNull ParsedMainComponent component, int flags, int userId) {
        if (packageState == null) {
            return false;
        }

        final AndroidPackage pkg = packageState.getPkg();
        if (pkg == null) {
            return false;
        }
        final PackageUserState userState = packageState.getUserStateOrDefault(userId);
        return PackageUserStateUtils.isMatch(userState, packageState.isSystem(),
                pkg.isEnabled(), component, flags);
    }
}
