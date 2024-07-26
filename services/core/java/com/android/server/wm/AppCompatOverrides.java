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

import com.android.server.wm.utils.OptPropFactory;

/**
 * Encapsulate logic related to operations guarded by an app override.
 */
public class AppCompatOverrides {

    @NonNull
    private final AppCompatOrientationOverrides mAppCompatOrientationOverrides;
    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;
    @NonNull
    private final AppCompatAspectRatioOverrides mAppCompatAspectRatioOverrides;
    @NonNull
    private final AppCompatFocusOverrides mAppCompatFocusOverrides;
    @NonNull
    private final AppCompatResizeOverrides mAppCompatResizeOverrides;

    AppCompatOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mAppCompatCameraOverrides = new AppCompatCameraOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder);
        mAppCompatOrientationOverrides = new AppCompatOrientationOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder, mAppCompatCameraOverrides);
        // TODO(b/341903757) Remove BooleanSuppliers after fixing dependency with reachability.
        mAppCompatAspectRatioOverrides = new AppCompatAspectRatioOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder,
                activityRecord.mLetterboxUiController::isDisplayFullScreenAndInPosture,
                activityRecord.mLetterboxUiController::getHorizontalPositionMultiplier);
        mAppCompatFocusOverrides = new AppCompatFocusOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder);
        mAppCompatResizeOverrides = new AppCompatResizeOverrides(activityRecord, optPropBuilder);
    }

    @NonNull
    AppCompatOrientationOverrides getAppCompatOrientationOverrides() {
        return mAppCompatOrientationOverrides;
    }

    @NonNull
    AppCompatCameraOverrides getAppCompatCameraOverrides() {
        return mAppCompatCameraOverrides;
    }

    @NonNull
    AppCompatAspectRatioOverrides getAppCompatAspectRatioOverrides() {
        return mAppCompatAspectRatioOverrides;
    }

    @NonNull
    AppCompatFocusOverrides getAppCompatFocusOverrides() {
        return mAppCompatFocusOverrides;
    }

    @NonNull
    AppCompatResizeOverrides getAppCompatResizeOverrides() {
        return mAppCompatResizeOverrides;
    }
}
