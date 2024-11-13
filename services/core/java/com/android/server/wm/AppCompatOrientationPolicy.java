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

/**
 * Contains all the logic related to orientation in the context of app compatibility
 */
class AppCompatOrientationPolicy {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatOrientationPolicy" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;

    AppCompatOrientationPolicy(@NonNull ActivityRecord activityRecord,
                               @NonNull AppCompatOverrides appCompatOverrides) {
        mActivityRecord = activityRecord;
        mAppCompatOverrides = appCompatOverrides;
    }

    @ActivityInfo.ScreenOrientation
    int overrideOrientationIfNeeded(@ActivityInfo.ScreenOrientation int candidate) {
        final DisplayContent displayContent = mActivityRecord.mDisplayContent;
        final boolean isIgnoreOrientationRequestEnabled = displayContent != null
                && displayContent.getIgnoreOrientationRequest();
        final boolean shouldApplyUserFullscreenOverride = mAppCompatOverrides
                .getAppCompatAspectRatioOverrides().shouldApplyUserFullscreenOverride();
        final boolean isCameraActive = displayContent != null
                && displayContent.mAppCompatCameraPolicy.isCameraActive(mActivityRecord,
                        /* mustBeFullscreen */ true);
        if (shouldApplyUserFullscreenOverride && isIgnoreOrientationRequestEnabled
                // Do not override orientation to fullscreen for camera activities.
                // Fixed-orientation activities are rarely tested in other orientations, and it
                // often results in sideways or stretched previews. As the camera compat treatment
                // targets fixed-orientation activities, overriding the orientation disables the
                // treatment.
                && !isCameraActive) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate)
                    + " for " + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_USER)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_USER;
        }

        // In some cases (e.g. Kids app) we need to map the candidate orientation to some other
        // orientation.
        candidate = mActivityRecord.mWmService.mapOrientationRequest(candidate);
        final boolean shouldApplyUserMinAspectRatioOverride = mAppCompatOverrides
                .getAppCompatAspectRatioOverrides().shouldApplyUserMinAspectRatioOverride();
        if (shouldApplyUserMinAspectRatioOverride && (!isFixedOrientation(candidate)
                || candidate == SCREEN_ORIENTATION_LOCKED)) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate)
                    + " for " + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_PORTRAIT)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_PORTRAIT;
        }

        if (mAppCompatOverrides.getAppCompatOrientationOverrides()
                .isAllowOrientationOverrideOptOut()) {
            return candidate;
        }

        if (displayContent != null
                && mAppCompatOverrides.getAppCompatCameraOverrides()
                    .isOverrideOrientationOnlyForCameraEnabled()
                && !displayContent.mAppCompatCameraPolicy
                    .isActivityEligibleForOrientationOverride(mActivityRecord)) {
            return candidate;
        }

        // mUserAspectRatio is always initialized first in shouldApplyUserFullscreenOverride(),
        // which will always come first before this check as user override > device
        // manufacturer override.
        final boolean isSystemOverrideToFullscreenEnabled = mAppCompatOverrides
                .getAppCompatAspectRatioOverrides().isSystemOverrideToFullscreenEnabled();
        if (isSystemOverrideToFullscreenEnabled && isIgnoreOrientationRequestEnabled
                // Do not override orientation to fullscreen for camera activities.
                // Fixed-orientation activities are rarely tested in other orientations, and it
                // often results in sideways or stretched previews. As the camera compat treatment
                // targets fixed-orientation activities, overriding the orientation disables the
                // treatment.
                && !isCameraActive) {
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

    /**
     * Whether should ignore app requested orientation in response to an app
     * calling {@link android.app.Activity#setRequestedOrientation}.
     *
     * <p>This is needed to avoid getting into {@link android.app.Activity#setRequestedOrientation}
     * loop when {@link DisplayContent#getIgnoreOrientationRequest} is enabled or device has
     * landscape natural orientation which app developers don't expect. For example, the loop can
     * look like this:
     * <ol>
     *     <li>App sets default orientation to "unspecified" at runtime
     *     <li>App requests to "portrait" after checking some condition (e.g. display rotation).
     *     <li>(2) leads to fullscreen -> letterboxed bounds change and activity relaunch because
     *     app can't handle the corresponding config changes.
     *     <li>Loop goes back to (1)
     * </ol>
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Opt-out component property isn't enabled
     *     <li>Opt-in component property or per-app override are enabled
     *     <li>Activity is relaunched after {@link android.app.Activity#setRequestedOrientation}
     *     call from an app or camera compat force rotation treatment is active for the activity.
     *     <li>Orientation request loop detected and is not letterboxed for fixed orientation
     * </ul>
     */
    boolean shouldIgnoreRequestedOrientation(
            @ActivityInfo.ScreenOrientation int requestedOrientation) {
        final AppCompatOrientationOverrides orientationOverrides =
                mAppCompatOverrides.getAppCompatOrientationOverrides();
        if (orientationOverrides.shouldEnableIgnoreOrientationRequest()) {
            if (orientationOverrides.getIsRelaunchingAfterRequestedOrientationChanged()) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to relaunching after setRequestedOrientation for "
                        + mActivityRecord);
                return true;
            }
            final AppCompatCameraPolicy cameraPolicy = mActivityRecord.mAppCompatController
                    .getAppCompatCameraPolicy();
            if (cameraPolicy != null
                    && cameraPolicy.isTreatmentEnabledForActivity(mActivityRecord)) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to camera compat treatment for " + mActivityRecord);
                return true;
            }
        }
        if (orientationOverrides.shouldIgnoreOrientationRequestLoop()) {
            Slog.w(TAG, "Ignoring orientation update to "
                    + screenOrientationToString(requestedOrientation)
                    + " as orientation request loop was detected for "
                    + mActivityRecord);
            return true;
        }
        return false;
    }

}
