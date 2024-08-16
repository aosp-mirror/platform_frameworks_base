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

import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.AppCompatUtils.asLazy;
import static com.android.server.wm.AppCompatUtils.isChangeEnabled;

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.utils.OptPropFactory;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Encapsulates all the configurations and overrides about orientation used by
 * {@link AppCompatOrientationPolicy}.
 */
class AppCompatOrientationOverrides {

    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "AppCompatOrientationOverrides" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;
    @NonNull
    private final OptPropFactory.OptProp mIgnoreRequestedOrientationOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowOrientationOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowDisplayOrientationOverrideOptProp;

    @NonNull
    final OrientationOverridesState mOrientationOverridesState;

    AppCompatOrientationOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder,
            @NonNull AppCompatCameraOverrides appCompatCameraOverrides) {
        mActivityRecord = activityRecord;
        mAppCompatCameraOverrides = appCompatCameraOverrides;
        mOrientationOverridesState = new OrientationOverridesState(mActivityRecord,
                System::currentTimeMillis);
        final BooleanSupplier isPolicyForIgnoringRequestedOrientationEnabled = asLazy(
                appCompatConfiguration::isPolicyForIgnoringRequestedOrientationEnabled);
        mIgnoreRequestedOrientationOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION,
                isPolicyForIgnoringRequestedOrientationEnabled);
        mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED,
                isPolicyForIgnoringRequestedOrientationEnabled);
        mAllowOrientationOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
        mAllowDisplayOrientationOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE,
                () -> mActivityRecord.mDisplayContent != null
                        && mActivityRecord.getTask() != null
                        && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest()
                        && !mActivityRecord.getTask().inMultiWindowMode()
                        && mActivityRecord.mDisplayContent.getNaturalOrientation()
                            == ORIENTATION_LANDSCAPE
        );
    }

    boolean shouldEnableIgnoreOrientationRequest() {
        return mIgnoreRequestedOrientationOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION));
    }

    boolean isOverrideRespectRequestedOrientationEnabled() {
        return isChangeEnabled(mActivityRecord, OVERRIDE_RESPECT_REQUESTED_ORIENTATION);
    }

    /**
     * Whether an app is calling {@link android.app.Activity#setRequestedOrientation}
     * in a loop and orientation request should be ignored.
     *
     * <p>This should only be called once in response to
     * {@link android.app.Activity#setRequestedOrientation}. See
     * {@link #shouldIgnoreRequestedOrientation} for more details.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     *     <li>App has requested orientation more than 2 times within 1-second
     *     timer and activity is not letterboxed for fixed orientation
     * </ul>
     */
    boolean shouldIgnoreOrientationRequestLoop() {
        final boolean loopDetectionEnabled = isCompatChangeEnabled(
                OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);
        if (!mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(loopDetectionEnabled)) {
            return false;
        }
        mOrientationOverridesState.updateOrientationRequestLoopState();

        return mOrientationOverridesState.shouldIgnoreRequestInLoop()
                && !mActivityRecord.mAppCompatController.getAppCompatAspectRatioPolicy()
                    .isLetterboxedForFixedOrientationAndAspectRatio();
    }

    /**
     * Whether should fix display orientation to landscape natural orientation when a task is
     * fullscreen and the display is ignoring orientation requests.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Opt-in per-app override is enabled
     *     <li>Task is in fullscreen.
     *     <li>{@link DisplayContent#getIgnoreOrientationRequest} is enabled
     *     <li>Natural orientation of the display is landscape.
     * </ul>
     */
    boolean shouldUseDisplayLandscapeNaturalOrientation() {
        return mAllowDisplayOrientationOverrideOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(
                        isChangeEnabled(mActivityRecord,
                                OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION));
    }

    /**
     * Sets whether an activity is relaunching after the app has called {@link
     * android.app.Activity#setRequestedOrientation}.
     */
    void setRelaunchingAfterRequestedOrientationChanged(boolean isRelaunching) {
        mOrientationOverridesState
                .mIsRelaunchingAfterRequestedOrientationChanged = isRelaunching;
    }

    boolean getIsRelaunchingAfterRequestedOrientationChanged() {
        return mOrientationOverridesState.mIsRelaunchingAfterRequestedOrientationChanged;
    }

    boolean isAllowOrientationOverrideOptOut() {
        return mAllowOrientationOverrideOptProp.isFalse();
    }

    @VisibleForTesting
    int getSetOrientationRequestCounter() {
        return mOrientationOverridesState.mSetOrientationRequestCounter;
    }

    private boolean isCompatChangeEnabled(long overrideChangeId) {
        return mActivityRecord.info.isChangeEnabled(overrideChangeId);
    }

    static class OrientationOverridesState {
        // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR
        final boolean mIsOverrideToNosensorOrientationEnabled;
        // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT
        final boolean mIsOverrideToPortraitOrientationEnabled;
        // Corresponds to OVERRIDE_ANY_ORIENTATION
        final boolean mIsOverrideAnyOrientationEnabled;
        // Corresponds to OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE
        final boolean mIsOverrideToReverseLandscapeOrientationEnabled;

        private boolean mIsRelaunchingAfterRequestedOrientationChanged;

        // Used to determine reset of mSetOrientationRequestCounter if next app requested
        // orientation is after timeout value
        @VisibleForTesting
        static final int SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS = 1000;
        // Minimum value of mSetOrientationRequestCounter before qualifying as orientation request
        // loop
        @VisibleForTesting
        static final int MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP = 2;
        // Updated when ActivityRecord#setRequestedOrientation is called
        private long mTimeMsLastSetOrientationRequest = 0;
        // Counter for ActivityRecord#setRequestedOrientation
        private int mSetOrientationRequestCounter = 0;
        @VisibleForTesting
        LongSupplier mCurrentTimeMillisSupplier;

        OrientationOverridesState(@NonNull ActivityRecord activityRecord,
                @NonNull LongSupplier currentTimeMillisSupplier) {
            mCurrentTimeMillisSupplier = currentTimeMillisSupplier;
            mIsOverrideToNosensorOrientationEnabled =
                    activityRecord.info.isChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR);
            mIsOverrideToPortraitOrientationEnabled =
                    activityRecord.info.isChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT);
            mIsOverrideAnyOrientationEnabled =
                    activityRecord.info.isChangeEnabled(OVERRIDE_ANY_ORIENTATION);
            mIsOverrideToReverseLandscapeOrientationEnabled = activityRecord.info
                    .isChangeEnabled(OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE);
        }

        /**
         * @return {@code true} if we should start ignoring orientation in a orientation request
         * loop.
         */
        boolean shouldIgnoreRequestInLoop() {
            return mSetOrientationRequestCounter >= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP;
        }

        /**
         * Updates the orientation request counter using a specific timeout.
         */
        void updateOrientationRequestLoopState() {
            final long currTimeMs = mCurrentTimeMillisSupplier.getAsLong();
            final long elapsedTime = currTimeMs - mTimeMsLastSetOrientationRequest;
            if (elapsedTime < SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS) {
                mSetOrientationRequestCounter++;
            } else {
                mSetOrientationRequestCounter = 0;
            }
            mTimeMsLastSetOrientationRequest = currTimeMs;
        }
    }
}
