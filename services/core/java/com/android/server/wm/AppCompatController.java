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

import android.annotation.NonNull;
import android.content.pm.PackageManager;

import com.android.server.wm.utils.OptPropFactory;

/**
 * Allows the interaction with all the app compat policies and configurations
 */
class AppCompatController {

    @NonNull
    private final TransparentPolicy mTransparentPolicy;
    @NonNull
    private final AppCompatOrientationPolicy mOrientationPolicy;
    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;
    @NonNull
    private final AppCompatCameraPolicy mAppCompatCameraPolicy;

    AppCompatController(@NonNull WindowManagerService wmService,
                        @NonNull ActivityRecord activityRecord) {
        final PackageManager packageManager = wmService.mContext.getPackageManager();
        final OptPropFactory optPropBuilder = new OptPropFactory(packageManager,
                activityRecord.packageName);
        mTransparentPolicy = new TransparentPolicy(activityRecord,
                wmService.mLetterboxConfiguration);
        mAppCompatOverrides = new AppCompatOverrides(activityRecord,
                wmService.mLetterboxConfiguration, optPropBuilder);
        // TODO(b/341903757) Remove BooleanSuppliers after fixing dependency with aspectRatio.
        final LetterboxUiController tmpController = activityRecord.mLetterboxUiController;
        mOrientationPolicy = new AppCompatOrientationPolicy(activityRecord,
                mAppCompatOverrides, tmpController::shouldApplyUserFullscreenOverride,
                tmpController::shouldApplyUserMinAspectRatioOverride,
                tmpController::isSystemOverrideToFullscreenEnabled);
        mAppCompatCameraPolicy = new AppCompatCameraPolicy(activityRecord,
                mAppCompatOverrides.getAppCompatCameraOverrides());
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
    AppCompatCameraPolicy getAppCompatCameraPolicy() {
        return mAppCompatCameraPolicy;
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
}
