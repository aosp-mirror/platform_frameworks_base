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
 * Encapsulate logic related to operations guarded by an app override.
 */
public class AppCompatOverrides {

    @NonNull
    private final AppCompatOrientationOverrides mOrientationOverrides;
    @NonNull
    private final AppCompatCameraOverrides mCameraOverrides;
    @NonNull
    private final AppCompatAspectRatioOverrides mAspectRatioOverrides;
    @NonNull
    private final AppCompatFocusOverrides mFocusOverrides;
    @NonNull
    private final AppCompatResizeOverrides mResizeOverrides;
    @NonNull
    private final AppCompatReachabilityOverrides mReachabilityOverrides;
    @NonNull
    private final AppCompatLetterboxOverrides mLetterboxOverrides;

    AppCompatOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull PackageManager packageManager,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder,
            @NonNull AppCompatDeviceStateQuery appCompatDeviceStateQuery) {
        mCameraOverrides = new AppCompatCameraOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder);
        mOrientationOverrides = new AppCompatOrientationOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder, mCameraOverrides);
        mReachabilityOverrides = new AppCompatReachabilityOverrides(activityRecord,
                appCompatConfiguration, appCompatDeviceStateQuery);
        mAspectRatioOverrides = new AppCompatAspectRatioOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder, appCompatDeviceStateQuery,
                mReachabilityOverrides);
        mFocusOverrides = new AppCompatFocusOverrides(activityRecord, appCompatConfiguration,
                optPropBuilder);
        mResizeOverrides = new AppCompatResizeOverrides(activityRecord, packageManager,
                optPropBuilder);
        mLetterboxOverrides = new AppCompatLetterboxOverrides(activityRecord,
                appCompatConfiguration);
    }

    @NonNull
    AppCompatOrientationOverrides getOrientationOverrides() {
        return mOrientationOverrides;
    }

    @NonNull
    AppCompatCameraOverrides getCameraOverrides() {
        return mCameraOverrides;
    }

    @NonNull
    AppCompatAspectRatioOverrides getAspectRatioOverrides() {
        return mAspectRatioOverrides;
    }

    @NonNull
    AppCompatFocusOverrides getFocusOverrides() {
        return mFocusOverrides;
    }

    @NonNull
    AppCompatResizeOverrides getResizeOverrides() {
        return mResizeOverrides;
    }

    @NonNull
    AppCompatReachabilityOverrides getReachabilityOverrides() {
        return mReachabilityOverrides;
    }

    @NonNull
    AppCompatLetterboxOverrides getLetterboxOverrides() {
        return mLetterboxOverrides;
    }
}
