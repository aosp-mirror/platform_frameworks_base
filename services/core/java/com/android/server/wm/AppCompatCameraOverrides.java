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

import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_NONE;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.app.CameraCompatTaskInfo.FreeformCameraCompatMode;

import com.android.server.wm.utils.OptPropFactory;
import com.android.window.flags.Flags;

import java.util.function.BooleanSupplier;

/**
 * Encapsulates app compat configurations and overrides related to camera.
 */
class AppCompatCameraOverrides {

    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "AppCompatCameraOverrides" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatCameraOverridesState mAppCompatCameraOverridesState;
    @NonNull
    private final LetterboxConfiguration mLetterboxConfiguration;
    @NonNull
    private final OptPropFactory.OptProp mAllowMinAspectRatioOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mCameraCompatAllowRefreshOptProp;
    @NonNull
    private final OptPropFactory.OptProp mCameraCompatEnableRefreshViaPauseOptProp;
    @NonNull
    private final OptPropFactory.OptProp mCameraCompatAllowForceRotationOptProp;

    AppCompatCameraOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mActivityRecord = activityRecord;
        mLetterboxConfiguration = letterboxConfiguration;
        mAppCompatCameraOverridesState = new AppCompatCameraOverridesState();
        mAllowMinAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
        final BooleanSupplier isCameraCompatTreatmentEnabled = AppCompatUtils.asLazy(
                mLetterboxConfiguration::isCameraCompatTreatmentEnabled);
        mCameraCompatAllowRefreshOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH,
                isCameraCompatTreatmentEnabled);
        mCameraCompatEnableRefreshViaPauseOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE,
                isCameraCompatTreatmentEnabled);
        mCameraCompatAllowForceRotationOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION,
                isCameraCompatTreatmentEnabled);
    }

    /**
     * Whether we should apply the min aspect ratio per-app override only when an app is connected
     * to the camera.
     * When this override is applied the min aspect ratio given in the app's manifest will be
     * overridden to the largest enabled aspect ratio treatment unless the app's manifest value
     * is higher. The treatment will also apply if no value is provided in the manifest.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideMinAspectRatioForCamera() {
        return mActivityRecord.isCameraActive()
                && mAllowMinAspectRatioOverrideOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(
                        isCompatChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA));
    }

    /**
     * Whether activity is eligible for activity "refresh" after camera compat force rotation
     * treatment. See {@link DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity isn't opted out by the device manufacturer with override or by the app
     *     developers with the component property.
     * </ul>
     */
    boolean shouldRefreshActivityForCameraCompat() {
        return mCameraCompatAllowRefreshOptProp.shouldEnableWithOptOutOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH));
    }

    /**
     * Whether activity should be "refreshed" after the camera compat force rotation treatment
     * using the "resumed -> paused -> resumed" cycle rather than the "resumed -> ... -> stopped
     * -> ... -> resumed" cycle. See {@link DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity "refresh" via "resumed -> paused -> resumed" cycle isn't disabled with the
     *     component property by the app developers.
     *     <li>Activity "refresh" via "resumed -> paused -> resumed" cycle is enabled by the device
     *     manufacturer with override / by the app developers with the component property.
     * </ul>
     */
    boolean shouldRefreshActivityViaPauseForCameraCompat() {
        return mCameraCompatEnableRefreshViaPauseOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE));
    }

    /**
     * Whether activity is eligible for camera compat force rotation treatment. See {@link
     * DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity isn't opted out by the device manufacturer with override or by the app
     *     developers with the component property.
     * </ul>
     */
    boolean shouldForceRotateForCameraCompat() {
        return mCameraCompatAllowForceRotationOptProp.shouldEnableWithOptOutOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION));
    }

    /**
     * Whether activity is eligible for camera compatibility free-form treatment.
     *
     * <p>The treatment is applied to a fixed-orientation camera activity in free-form windowing
     * mode. The treatment letterboxes or pillarboxes the activity to the expected orientation and
     * provides changes to the camera and display orientation signals to match those expected on a
     * portrait device in that orientation (for example, on a standard phone).
     *
     * <p>The treatment is enabled when the following conditions are met:
     * <ul>
     * <li>Property gating the camera compatibility free-form treatment is enabled.
     * <li>Activity isn't opted out by the device manufacturer with override.
     * </ul>
     */
    boolean shouldApplyFreeformTreatmentForCameraCompat() {
        return Flags.cameraCompatForFreeform() && !isCompatChangeEnabled(
                OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT);
    }

    /**
     * @return {@code true} if the configuration needs to be recomputed after a camera state update.
     */
    boolean shouldRecomputeConfigurationForCameraCompat() {
        return isOverrideOrientationOnlyForCameraEnabled()
                || isCameraCompatSplitScreenAspectRatioAllowed()
                || shouldOverrideMinAspectRatioForCamera();
    }

    boolean isOverrideOrientationOnlyForCameraEnabled() {
        return isCompatChangeEnabled(OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA);
    }

    /**
     * Whether activity "refresh" was requested but not finished in {@link #activityResumedLocked}.
     */
    boolean isRefreshRequested() {
        return mAppCompatCameraOverridesState.mIsRefreshRequested;
    }

    /**
     * @param isRequested Whether activity "refresh" was requested but not finished
     *                    in {@link #activityResumedLocked}.
     */
    void setIsRefreshRequested(boolean isRequested) {
        mAppCompatCameraOverridesState.mIsRefreshRequested = isRequested;
    }

    /**
     * Whether we use split screen aspect ratio for the activity when camera compat treatment
     * is active because the corresponding config is enabled and activity supports resizing.
     */
    boolean isCameraCompatSplitScreenAspectRatioAllowed() {
        return mLetterboxConfiguration.isCameraCompatSplitScreenAspectRatioEnabled()
                && !mActivityRecord.shouldCreateCompatDisplayInsets();
    }

    @FreeformCameraCompatMode
    int getFreeformCameraCompatMode() {
        return mAppCompatCameraOverridesState.mFreeformCameraCompatMode;
    }

    void setFreeformCameraCompatMode(@FreeformCameraCompatMode int freeformCameraCompatMode) {
        mAppCompatCameraOverridesState.mFreeformCameraCompatMode = freeformCameraCompatMode;
    }

    private boolean isCompatChangeEnabled(long overrideChangeId) {
        return mActivityRecord.info.isChangeEnabled(overrideChangeId);
    }

    static class AppCompatCameraOverridesState {
        // Whether activity "refresh" was requested but not finished in
        // ActivityRecord#activityResumedLocked following the camera compat force rotation in
        // DisplayRotationCompatPolicy.
        private boolean mIsRefreshRequested;

        @FreeformCameraCompatMode
        private int mFreeformCameraCompatMode = CAMERA_COMPAT_FREEFORM_NONE;
    }
}
