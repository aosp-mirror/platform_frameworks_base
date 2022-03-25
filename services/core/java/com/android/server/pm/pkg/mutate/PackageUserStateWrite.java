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
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;

import com.android.server.pm.pkg.SuspendParams;

public interface PackageUserStateWrite {

    @NonNull
    PackageUserStateWrite setInstalled(boolean installed);

    @NonNull
    PackageUserStateWrite setUninstallReason(@PackageManager.UninstallReason int reason);

    @NonNull
    PackageUserStateWrite setDistractionFlags(
            @PackageManager.DistractionRestriction int restrictionFlags);

    @NonNull
    PackageUserStateWrite putSuspendParams(@NonNull String suspendingPackage,
            @Nullable SuspendParams suspendParams);

    @NonNull
    PackageUserStateWrite removeSuspension(@NonNull String suspendingPackage);

    @NonNull
    PackageUserStateWrite setHidden(boolean hidden);

    @NonNull
    PackageUserStateWrite setStopped(boolean stopped);

    @NonNull
    PackageUserStateWrite setNotLaunched(boolean notLaunched);

    @NonNull
    PackageUserStateWrite setOverlayPaths(@Nullable OverlayPaths overlayPaths);

    @NonNull
    PackageUserStateWrite setOverlayPathsForLibrary(@NonNull String libraryName,
            @Nullable OverlayPaths overlayPaths);

    @NonNull
    PackageUserStateWrite setHarmfulAppWarning(@Nullable String warning);

    @NonNull
    PackageUserStateWrite setSplashScreenTheme(@Nullable String theme);

    @NonNull
    PackageUserStateWrite setComponentLabelIcon(@NonNull ComponentName componentName,
            @Nullable String nonLocalizedLabel, @Nullable Integer icon);
}
