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

import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;

import static com.android.server.wm.AppCompatUtils.isChangeEnabled;

import android.annotation.NonNull;

import com.android.server.wm.utils.OptPropFactory;

/**
 * Encapsulate logic related to operations guarded by an app override.
 */
public class AppCompatOverrides {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final OptPropFactory.OptProp mAllowForceResizeOverrideOptProp;
    @NonNull
    private final AppCompatOrientationOverrides mAppCompatOrientationOverrides;
    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;
    @NonNull
    private final AppCompatAspectRatioOverrides mAppCompatAspectRatioOverrides;
    @NonNull
    private final AppCompatFocusOverrides mAppCompatFocusOverrides;

    AppCompatOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mActivityRecord = activityRecord;

        mAppCompatCameraOverrides = new AppCompatCameraOverrides(mActivityRecord,
                appCompatConfiguration, optPropBuilder);
        mAppCompatOrientationOverrides = new AppCompatOrientationOverrides(mActivityRecord,
                appCompatConfiguration, optPropBuilder, mAppCompatCameraOverrides);
        // TODO(b/341903757) Remove BooleanSuppliers after fixing dependency with reachability.
        mAppCompatAspectRatioOverrides = new AppCompatAspectRatioOverrides(activityRecord,
                appCompatConfiguration, optPropBuilder,
                activityRecord.mLetterboxUiController::isDisplayFullScreenAndInPosture,
                activityRecord.mLetterboxUiController::getHorizontalPositionMultiplier);
        mAppCompatFocusOverrides = new AppCompatFocusOverrides(mActivityRecord,
                appCompatConfiguration, optPropBuilder);

        mAllowForceResizeOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
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

    /**
     * Whether we should apply the force resize per-app override. When this override is applied it
     * forces the packages it is applied to to be resizable. It won't change whether the app can be
     * put into multi-windowing mode, but allow the app to resize without going into size-compat
     * mode when the window container resizes, such as display size change or screen rotation.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isChangeEnabled(mActivityRecord, FORCE_RESIZE_APP));
    }

    /**
     * Whether we should apply the force non resize per-app override. When this override is applied
     * it forces the packages it is applied to to be non-resizable.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceNonResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isChangeEnabled(mActivityRecord, FORCE_NON_RESIZE_APP));
    }
}
