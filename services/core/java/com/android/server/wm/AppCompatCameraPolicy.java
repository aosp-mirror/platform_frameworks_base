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

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;

/**
 * Encapsulate the app compat logic related to camera.
 */
class AppCompatCameraPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "AppCompatCameraPolicy" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;

    AppCompatCameraPolicy(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatCameraOverrides appCompatCameraOverrides) {
        mActivityRecord = activityRecord;
        mAppCompatCameraOverrides = appCompatCameraOverrides;
    }

    void recomputeConfigurationForCameraCompatIfNeeded() {
        if (mAppCompatCameraOverrides.shouldRecomputeConfigurationForCameraCompat()) {
            mActivityRecord.recomputeConfiguration();
        }
    }
}
