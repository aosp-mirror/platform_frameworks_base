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
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;

import com.android.server.wm.utils.OptPropFactory;

/**
 * Encapsulate logic related to operations guarded by an app override.
 */
public class AppCompatOverrides {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppCompatOverrides" : TAG_ATM;

    @NonNull
    private final LetterboxConfiguration mLetterboxConfiguration;

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final OptPropFactory.OptProp mFakeFocusOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowOrientationOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowDisplayOrientationOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowMinAspectRatioOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowForceResizeOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowUserAspectRatioOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowUserAspectRatioFullscreenOverrideOptProp;
    @NonNull
    private final AppCompatOrientationOverrides mAppCompatOrientationOverrides;
    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;

    AppCompatOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mLetterboxConfiguration = letterboxConfiguration;
        mActivityRecord = activityRecord;

        mAppCompatOrientationOverrides = new AppCompatOrientationOverrides(mActivityRecord,
                mLetterboxConfiguration, optPropBuilder);
        mAppCompatCameraOverrides = new AppCompatCameraOverrides(mActivityRecord,
                mLetterboxConfiguration, optPropBuilder);

        mFakeFocusOptProp = optPropBuilder.create(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS,
                mLetterboxConfiguration::isCompatFakeFocusEnabled);


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

        mAllowMinAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
        mAllowForceResizeOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
        mAllowUserAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE,
                mLetterboxConfiguration::isUserAppAspectRatioSettingsEnabled);
        mAllowUserAspectRatioFullscreenOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE,
                mLetterboxConfiguration::isUserAppAspectRatioFullscreenEnabled);
    }

    /**
     * @return {@code true} if the App Compat Camera Policy is active for the current activity.
     */
    boolean isCameraCompatTreatmentActive() {
        final DisplayContent displayContent = mActivityRecord.mDisplayContent;
        if (displayContent == null) {
            return false;
        }
        return displayContent.mDisplayRotationCompatPolicy != null
                && displayContent.mDisplayRotationCompatPolicy
                    .isTreatmentEnabledForActivity(mActivityRecord);
    }

    @NonNull
    AppCompatOrientationOverrides getAppCompatOrientationOverrides() {
        return mAppCompatOrientationOverrides;
    }

    @NonNull
    AppCompatCameraOverrides getAppCompatCameraOverrides() {
        return mAppCompatCameraOverrides;
    }

    /**
     * Whether sending compat fake focus for split screen resumed activities is enabled. Needed
     * because some game engines wait to get focus before drawing the content of the app which isn't
     * guaranteed by default in multi-window modes.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Component property is NOT set to false
     *     <li>Component property is set to true or per-app override is enabled
     * </ul>
     */
    boolean shouldSendFakeFocus() {
        return mFakeFocusOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS));
    }

    boolean isSystemOverrideToFullscreenEnabled(int userAspectRatio) {
        return isCompatChangeEnabled(OVERRIDE_ANY_ORIENTATION_TO_USER)
                && !mAllowOrientationOverrideOptProp.isFalse()
                && (userAspectRatio == USER_MIN_ASPECT_RATIO_UNSET
                    || userAspectRatio == USER_MIN_ASPECT_RATIO_FULLSCREEN);
    }

    boolean isAllowOrientationOverrideOptOut() {
        return mAllowOrientationOverrideOptProp.isFalse();
    }

    /**
     * Whether we should apply the min aspect ratio per-app override. When this override is applied
     * the min aspect ratio given in the app's manifest will be overridden to the largest enabled
     * aspect ratio treatment unless the app's manifest value is higher. The treatment will also
     * apply if no value is provided in the manifest.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideMinAspectRatio() {
        return mAllowMinAspectRatioOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isCompatChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO));
    }

    boolean isOverrideRespectRequestedOrientationEnabled() {
        return isCompatChangeEnabled(OVERRIDE_RESPECT_REQUESTED_ORIENTATION);
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
                        isCompatChangeEnabled(OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION));
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
                isCompatChangeEnabled(FORCE_RESIZE_APP));
    }

    /**
     * Whether we should enable users to resize the current app.
     */
    boolean shouldEnableUserAspectRatioSettings() {
        // We use mBooleanPropertyAllowUserAspectRatioOverride to allow apps to opt-out which has
        // effect only if explicitly false. If mBooleanPropertyAllowUserAspectRatioOverride is null,
        // the current app doesn't opt-out so the first part of the predicate is true.
        return !mAllowUserAspectRatioOverrideOptProp.isFalse()
                && mLetterboxConfiguration.isUserAppAspectRatioSettingsEnabled()
                && mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest();
    }

    boolean isUserFullscreenOverrideEnabled() {
        if (mAllowUserAspectRatioOverrideOptProp.isFalse()
                || mAllowUserAspectRatioFullscreenOverrideOptProp.isFalse()
                || !mLetterboxConfiguration.isUserAppAspectRatioFullscreenEnabled()) {
            return false;
        }
        return true;
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
                isCompatChangeEnabled(FORCE_NON_RESIZE_APP));
    }

    private boolean isCompatChangeEnabled(long overrideChangeId) {
        return mActivityRecord.info.isChangeEnabled(overrideChangeId);
    }
}
