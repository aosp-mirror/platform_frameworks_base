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

package com.android.server.pm.pkg.mutate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.ArraySet;

public interface PackageStateWrite {

    void onChanged();

    @NonNull
    PackageUserStateWrite userState(@UserIdInt int userId);

    @NonNull
    PackageStateWrite setLastPackageUsageTime(@PackageManager.NotifyReason int reason,
            long timeInMillis);

    @NonNull
    PackageStateWrite setHiddenUntilInstalled(boolean hiddenUntilInstalled);

    @NonNull
    PackageStateWrite setRequiredForSystemUser(boolean requiredForSystemUser);

    @NonNull
    PackageStateWrite setMimeGroup(@NonNull String mimeGroup, @NonNull ArraySet<String> mimeTypes);

    @NonNull
    PackageStateWrite setCategoryOverride(@ApplicationInfo.Category int category);

    /** set 16Kb App compat mode. @see ApplicationInfo.PageSizeAppCompatFlags */
    @NonNull
    PackageStateWrite setPageSizeAppCompatFlags(@ApplicationInfo.PageSizeAppCompatFlags int mode);

    @NonNull
    PackageStateWrite setUpdateAvailable(boolean updateAvailable);

    @NonNull
    PackageStateWrite setLoadingProgress(float progress);

    @NonNull
    PackageStateWrite setLoadingCompletedTime(long loadingCompletedTime);

    @NonNull
    PackageStateWrite setOverrideSeInfo(@Nullable String newSeInfo);

    @NonNull
    PackageStateWrite setInstaller(@Nullable String installerPackageName, int installerPackageUid);

    @NonNull
    PackageStateWrite setUpdateOwner(@Nullable String updateOwnerPackageName);
}
