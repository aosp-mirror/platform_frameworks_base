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
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
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
    private final OptPropFactory.OptProp mAllowForceResizeOverrideOptProp;
    @NonNull
    private final AppCompatOrientationOverrides mAppCompatOrientationOverrides;
    @NonNull
    private final AppCompatCameraOverrides mAppCompatCameraOverrides;
    @NonNull
    private final AppCompatAspectRatioOverrides mAppCompatAspectRatioOverrides;

    AppCompatOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull LetterboxConfiguration letterboxConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mLetterboxConfiguration = letterboxConfiguration;
        mActivityRecord = activityRecord;

        mAppCompatCameraOverrides = new AppCompatCameraOverrides(mActivityRecord,
                mLetterboxConfiguration, optPropBuilder);
        mAppCompatOrientationOverrides = new AppCompatOrientationOverrides(mActivityRecord,
                mLetterboxConfiguration, optPropBuilder, mAppCompatCameraOverrides);
        // TODO(b/341903757) Remove BooleanSuppliers after fixing dependency with reachability.
        mAppCompatAspectRatioOverrides = new AppCompatAspectRatioOverrides(activityRecord,
                mLetterboxConfiguration, optPropBuilder,
                activityRecord.mLetterboxUiController::isDisplayFullScreenAndInPosture,
                activityRecord.mLetterboxUiController::getHorizontalPositionMultiplier);

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

    boolean isAllowOrientationOverrideOptOut() {
        return mAllowOrientationOverrideOptProp.isFalse();
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
