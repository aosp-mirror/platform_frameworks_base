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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
import static android.content.pm.ActivityInfo.isFixedOrientation;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.screenOrientationToString;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.util.Slog;

import java.util.function.BooleanSupplier;

/**
 * Contains all the logic related to orientation in the context of app compatibility
 */
class AppCompatOrientationPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatOrientationPolicy" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;

    @NonNull
    private final BooleanSupplier mShouldApplyUserFullscreenOverride;
    @NonNull
    private final BooleanSupplier mShouldApplyUserMinAspectRatioOverride;
    @NonNull
    private final BooleanSupplier mIsSystemOverrideToFullscreenEnabled;

    // TODO(b/341903757) Remove BooleanSuppliers after fixing dependency with spectRatio component
    AppCompatOrientationPolicy(@NonNull ActivityRecord activityRecord,
                               @NonNull AppCompatOverrides appCompatOverrides,
                               @NonNull BooleanSupplier shouldApplyUserFullscreenOverride,
                               @NonNull BooleanSupplier shouldApplyUserMinAspectRatioOverride,
                               @NonNull BooleanSupplier isSystemOverrideToFullscreenEnabled) {
        mActivityRecord = activityRecord;
        mAppCompatOverrides = appCompatOverrides;
        mShouldApplyUserFullscreenOverride = shouldApplyUserFullscreenOverride;
        mShouldApplyUserMinAspectRatioOverride = shouldApplyUserMinAspectRatioOverride;
        mIsSystemOverrideToFullscreenEnabled = isSystemOverrideToFullscreenEnabled;
    }

    @ActivityInfo.ScreenOrientation
    int overrideOrientationIfNeeded(@ActivityInfo.ScreenOrientation int candidate) {
        final DisplayContent displayContent = mActivityRecord.mDisplayContent;
        final boolean isIgnoreOrientationRequestEnabled = displayContent != null
                && displayContent.getIgnoreOrientationRequest();
        if (mShouldApplyUserFullscreenOverride.getAsBoolean() && isIgnoreOrientationRequestEnabled
                // Do not override orientation to fullscreen for camera activities.
                // Fixed-orientation activities are rarely tested in other orientations, and it
                // often results in sideways or stretched previews. As the camera compat treatment
                // targets fixed-orientation activities, overriding the orientation disables the
                // treatment.
                && !mActivityRecord.isCameraActive()) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate)
                    + " for " + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_USER)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_USER;
        }

        // In some cases (e.g. Kids app) we need to map the candidate orientation to some other
        // orientation.
        candidate = mActivityRecord.mWmService.mapOrientationRequest(candidate);

        if (mShouldApplyUserMinAspectRatioOverride.getAsBoolean() && (!isFixedOrientation(candidate)
                || candidate == SCREEN_ORIENTATION_LOCKED)) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate)
                    + " for " + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_PORTRAIT)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_PORTRAIT;
        }

        if (mAppCompatOverrides.isAllowOrientationOverrideOptOut()) {
            return candidate;
        }

        if (displayContent != null && mAppCompatOverrides.getAppCompatCameraOverrides()
                .isOverrideOrientationOnlyForCameraEnabled()
                    && (displayContent.mDisplayRotationCompatPolicy == null
                    || !displayContent.mDisplayRotationCompatPolicy
                        .isActivityEligibleForOrientationOverride(mActivityRecord))) {
            return candidate;
        }

        // mUserAspectRatio is always initialized first in shouldApplyUserFullscreenOverride(),
        // which will always come first before this check as user override > device
        // manufacturer override.
        if (mIsSystemOverrideToFullscreenEnabled.getAsBoolean() && isIgnoreOrientationRequestEnabled
                // Do not override orientation to fullscreen for camera activities.
                // Fixed-orientation activities are rarely tested in other orientations, and it
                // often results in sideways or stretched previews. As the camera compat treatment
                // targets fixed-orientation activities, overriding the orientation disables the
                // treatment.
                && !mActivityRecord.isCameraActive()) {
            Slog.v(TAG, "Requested orientation  " + screenOrientationToString(candidate)
                    + " for " + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_USER));
            return SCREEN_ORIENTATION_USER;
        }

        final AppCompatOrientationOverrides.OrientationOverridesState capabilityState =
                mAppCompatOverrides.getAppCompatOrientationOverrides()
                        .mOrientationOverridesState;

        if (capabilityState.mIsOverrideToReverseLandscapeOrientationEnabled
                && (isFixedOrientationLandscape(candidate)
                || capabilityState.mIsOverrideAnyOrientationEnabled)) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
            return SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }

        if (!capabilityState.mIsOverrideAnyOrientationEnabled && isFixedOrientation(candidate)) {
            return candidate;
        }

        if (capabilityState.mIsOverrideToPortraitOrientationEnabled) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_PORTRAIT));
            return SCREEN_ORIENTATION_PORTRAIT;
        }

        if (capabilityState.mIsOverrideToNosensorOrientationEnabled) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_NOSENSOR));
            return SCREEN_ORIENTATION_NOSENSOR;
        }

        return candidate;
    }

}
