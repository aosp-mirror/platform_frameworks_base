/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.wm;

import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY;

import android.annotation.NonNull;
import android.content.pm.PackageManager;

import com.android.server.wm.utils.OptPropFactory;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

/**
 * Allows the interaction with all the app compat policies and configurations
 */
class AppCompatController {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final TransparentPolicy mTransparentPolicy;
    @NonNull
    private final AppCompatOrientationPolicy mOrientationPolicy;
    @NonNull
    private final AppCompatAspectRatioPolicy mAppCompatAspectRatioPolicy;
    @NonNull
    private final AppCompatReachabilityPolicy mAppCompatReachabilityPolicy;
    @NonNull
    private final DesktopAppCompatAspectRatioPolicy mDesktopAppCompatAspectRatioPolicy;
    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;
    @NonNull
    private final AppCompatDeviceStateQuery mAppCompatDeviceStateQuery;
    @NonNull
    private final AppCompatLetterboxPolicy mAppCompatLetterboxPolicy;
    @NonNull
    private final AppCompatSizeCompatModePolicy mAppCompatSizeCompatModePolicy;
    @NonNull
    final BooleanSupplier mAllowRestrictedResizability;

    AppCompatController(@NonNull WindowManagerService wmService,
                        @NonNull ActivityRecord activityRecord) {
        mActivityRecord = activityRecord;
        final PackageManager packageManager = wmService.mContext.getPackageManager();
        final OptPropFactory optPropBuilder = new OptPropFactory(packageManager,
                activityRecord.packageName);
        mAppCompatDeviceStateQuery = new AppCompatDeviceStateQuery(activityRecord);
        mTransparentPolicy = new TransparentPolicy(activityRecord,
                wmService.mAppCompatConfiguration);
        mAppCompatOverrides = new AppCompatOverrides(activityRecord,
                wmService.mAppCompatConfiguration, optPropBuilder, mAppCompatDeviceStateQuery);
        mOrientationPolicy = new AppCompatOrientationPolicy(activityRecord, mAppCompatOverrides);
        mAppCompatAspectRatioPolicy = new AppCompatAspectRatioPolicy(activityRecord,
                mTransparentPolicy, mAppCompatOverrides);
        mAppCompatReachabilityPolicy = new AppCompatReachabilityPolicy(mActivityRecord,
                wmService.mAppCompatConfiguration);
        mAppCompatLetterboxPolicy = new AppCompatLetterboxPolicy(mActivityRecord,
                wmService.mAppCompatConfiguration);
        mDesktopAppCompatAspectRatioPolicy = new DesktopAppCompatAspectRatioPolicy(activityRecord,
                mAppCompatOverrides, mTransparentPolicy, wmService.mAppCompatConfiguration);
        mAppCompatSizeCompatModePolicy = new AppCompatSizeCompatModePolicy(mActivityRecord,
                mAppCompatOverrides);
        mAllowRestrictedResizability = AppCompatUtils.asLazy(() -> {
            // Application level.
            if (allowRestrictedResizability(packageManager, mActivityRecord.packageName)) {
                return true;
            }
            // Activity level.
            try {
                return packageManager.getPropertyAsUser(
                        PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY,
                        mActivityRecord.mActivityComponent.getPackageName(),
                        mActivityRecord.mActivityComponent.getClassName(),
                        mActivityRecord.mUserId).getBoolean();
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        });
    }

    static boolean allowRestrictedResizability(PackageManager pm, String packageName) {
        try {
            return pm.getProperty(PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY, packageName)
                    .getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @NonNull
    TransparentPolicy getTransparentPolicy() {
        return mTransparentPolicy;
    }

    @NonNull
    AppCompatOrientationPolicy getOrientationPolicy() {
        return mOrientationPolicy;
    }

    @NonNull
    AppCompatAspectRatioPolicy getAppCompatAspectRatioPolicy() {
        return mAppCompatAspectRatioPolicy;
    }

    @NonNull
    DesktopAppCompatAspectRatioPolicy getDesktopAppCompatAspectRatioPolicy() {
        return mDesktopAppCompatAspectRatioPolicy;
    }

    @NonNull
    AppCompatOverrides getAppCompatOverrides() {
        return mAppCompatOverrides;
    }

    @NonNull
    AppCompatOrientationOverrides getAppCompatOrientationOverrides() {
        return mAppCompatOverrides.getAppCompatOrientationOverrides();
    }

    @NonNull
    AppCompatCameraOverrides getAppCompatCameraOverrides() {
        return mAppCompatOverrides.getAppCompatCameraOverrides();
    }

    @NonNull
    AppCompatAspectRatioOverrides getAppCompatAspectRatioOverrides() {
        return mAppCompatOverrides.getAppCompatAspectRatioOverrides();
    }

    @NonNull
    AppCompatResizeOverrides getAppCompatResizeOverrides() {
        return mAppCompatOverrides.getAppCompatResizeOverrides();
    }

    @NonNull
    AppCompatReachabilityPolicy getAppCompatReachabilityPolicy() {
        return mAppCompatReachabilityPolicy;
    }

    @NonNull
    AppCompatLetterboxPolicy getAppCompatLetterboxPolicy() {
        return mAppCompatLetterboxPolicy;
    }

    @NonNull
    AppCompatFocusOverrides getAppCompatFocusOverrides() {
        return mAppCompatOverrides.getAppCompatFocusOverrides();
    }

    @NonNull
    AppCompatReachabilityOverrides getAppCompatReachabilityOverrides() {
        return mAppCompatOverrides.getAppCompatReachabilityOverrides();
    }

    @NonNull
    AppCompatDeviceStateQuery getAppCompatDeviceStateQuery() {
        return mAppCompatDeviceStateQuery;
    }

    @NonNull
    AppCompatLetterboxOverrides getAppCompatLetterboxOverrides() {
        return mAppCompatOverrides.getAppCompatLetterboxOverrides();
    }

    @NonNull
    AppCompatSizeCompatModePolicy getAppCompatSizeCompatModePolicy() {
        return mAppCompatSizeCompatModePolicy;
    }

    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        getTransparentPolicy().dump(pw, prefix);
        getAppCompatLetterboxPolicy().dump(pw, prefix);
        getAppCompatSizeCompatModePolicy().dump(pw, prefix);
    }

}
