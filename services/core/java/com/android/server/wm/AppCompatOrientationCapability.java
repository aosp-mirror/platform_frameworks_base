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
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.AppCompatCapability.asLazy;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.utils.OptPropFactory;

import java.util.function.BooleanSupplier;

class AppCompatOrientationCapability {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatCapability" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final OptPropFactory.OptProp mIgnoreRequestedOrientationOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp;

    @NonNull
    final OrientationCapabilityState mOrientationCapabilityState;

    AppCompatOrientationCapability(@NonNull OptPropFactory optPropBuilder,
                                   @NonNull LetterboxConfiguration letterboxConfiguration,
                                   @NonNull ActivityRecord activityRecord) {
        mActivityRecord = activityRecord;
        mOrientationCapabilityState = new OrientationCapabilityState(mActivityRecord);
        final BooleanSupplier isPolicyForIgnoringRequestedOrientationEnabled = asLazy(
                letterboxConfiguration::isPolicyForIgnoringRequestedOrientationEnabled);
        mIgnoreRequestedOrientationOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION,
                isPolicyForIgnoringRequestedOrientationEnabled);
        mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED,
                isPolicyForIgnoringRequestedOrientationEnabled);
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
        if (mIgnoreRequestedOrientationOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION))) {
            if (mOrientationCapabilityState.mIsRelaunchingAfterRequestedOrientationChanged) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to relaunching after setRequestedOrientation for "
                        + mActivityRecord);
                return true;
            }
            if (isCameraCompatTreatmentActive()) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to camera compat treatment for " + mActivityRecord);
                return true;
            }
        }

        if (shouldIgnoreOrientationRequestLoop()) {
            Slog.w(TAG, "Ignoring orientation update to "
                    + screenOrientationToString(requestedOrientation)
                    + " as orientation request loop was detected for "
                    + mActivityRecord);
            return true;
        }
        return false;
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
        mOrientationCapabilityState.updateOrientationRequestLoopState();

        return mOrientationCapabilityState.shouldIgnoreRequestInLoop()
                && !mActivityRecord.isLetterboxedForFixedOrientationAndAspectRatio();
    }

    /**
     * Sets whether an activity is relaunching after the app has called {@link
     * android.app.Activity#setRequestedOrientation}.
     */
    void setRelaunchingAfterRequestedOrientationChanged(boolean isRelaunching) {
        mOrientationCapabilityState
                .mIsRelaunchingAfterRequestedOrientationChanged = isRelaunching;
    }

    boolean getIsRelaunchingAfterRequestedOrientationChanged() {
        return mOrientationCapabilityState.mIsRelaunchingAfterRequestedOrientationChanged;
    }

    @VisibleForTesting
    int getSetOrientationRequestCounter() {
        return mOrientationCapabilityState.mSetOrientationRequestCounter;
    }

    private boolean isCompatChangeEnabled(long overrideChangeId) {
        return mActivityRecord.info.isChangeEnabled(overrideChangeId);
    }

    /**
     * @return {@code true} if the App Compat Camera Policy is active for the current activity.
     */
    // TODO(b/346253439): Remove after defining dependency with Camera capabilities.
    private boolean isCameraCompatTreatmentActive() {
        DisplayContent displayContent = mActivityRecord.mDisplayContent;
        if (displayContent == null) {
            return false;
        }
        return displayContent.mDisplayRotationCompatPolicy != null
                && displayContent.mDisplayRotationCompatPolicy
                .isTreatmentEnabledForActivity(mActivityRecord);
    }

    static class OrientationCapabilityState {
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

        OrientationCapabilityState(@NonNull ActivityRecord activityRecord) {
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
            final long currTimeMs = System.currentTimeMillis();
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
