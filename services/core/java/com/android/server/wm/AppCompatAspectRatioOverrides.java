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

import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_APP_DEFAULT;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_DISPLAY_SIZE;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_POSITION_MULTIPLIER_CENTER;
import static com.android.server.wm.AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.server.wm.AppCompatUtils.isChangeEnabled;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.utils.OptPropFactory;

/**
 * Encapsulates app compat configurations and overrides related to aspect ratio.
 */
class AppCompatAspectRatioOverrides {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "AppCompatAspectRatioOverrides" : TAG_ATM;

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;
    @NonNull
    private final UserAspectRatioState mUserAspectRatioState;

    @NonNull
    private final OptPropFactory.OptProp mAllowMinAspectRatioOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowUserAspectRatioOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowUserAspectRatioFullscreenOverrideOptProp;
    @NonNull
    private final OptPropFactory.OptProp mAllowOrientationOverrideOptProp;
    @NonNull
    private final AppCompatDeviceStateQuery mAppCompatDeviceStateQuery;
    @NonNull
    private final AppCompatReachabilityOverrides mAppCompatReachabilityOverrides;

    AppCompatAspectRatioOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder,
            @NonNull AppCompatDeviceStateQuery appCompatDeviceStateQuery,
            @NonNull AppCompatReachabilityOverrides appCompatReachabilityOverrides) {
        mActivityRecord = activityRecord;
        mAppCompatConfiguration = appCompatConfiguration;
        mAppCompatDeviceStateQuery = appCompatDeviceStateQuery;
        mUserAspectRatioState = new UserAspectRatioState();
        mAppCompatReachabilityOverrides = appCompatReachabilityOverrides;
        mAllowMinAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
        mAllowUserAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE,
                mAppCompatConfiguration::isUserAppAspectRatioSettingsEnabled);
        mAllowUserAspectRatioFullscreenOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE,
                mAppCompatConfiguration::isUserAppAspectRatioFullscreenEnabled);
        mAllowOrientationOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
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
                isChangeEnabled(mActivityRecord, OVERRIDE_MIN_ASPECT_RATIO));
    }

    /**
     * Whether we should apply the user aspect ratio override to the min aspect ratio for the
     * current app.
     */
    boolean shouldApplyUserMinAspectRatioOverride() {
        if (!shouldEnableUserAspectRatioSettings()) {
            return false;
        }

        mUserAspectRatioState.mUserAspectRatio = getUserMinAspectRatioOverrideCode();

        return mUserAspectRatioState.mUserAspectRatio != USER_MIN_ASPECT_RATIO_UNSET
                && mUserAspectRatioState.mUserAspectRatio != USER_MIN_ASPECT_RATIO_APP_DEFAULT
                && mUserAspectRatioState.mUserAspectRatio != USER_MIN_ASPECT_RATIO_FULLSCREEN;
    }

    boolean shouldApplyUserFullscreenOverride() {
        if (isUserFullscreenOverrideEnabled()) {
            mUserAspectRatioState.mUserAspectRatio = getUserMinAspectRatioOverrideCode();

            return mUserAspectRatioState.mUserAspectRatio == USER_MIN_ASPECT_RATIO_FULLSCREEN;
        }

        return false;
    }

    boolean isUserFullscreenOverrideEnabled() {
        if (mAllowUserAspectRatioOverrideOptProp.isFalse()
                || mAllowUserAspectRatioFullscreenOverrideOptProp.isFalse()
                || !mAppCompatConfiguration.isUserAppAspectRatioFullscreenEnabled()) {
            return false;
        }
        return true;
    }

    boolean isSystemOverrideToFullscreenEnabled() {
        return isChangeEnabled(mActivityRecord, OVERRIDE_ANY_ORIENTATION_TO_USER)
                && !mAllowOrientationOverrideOptProp.isFalse()
                && (mUserAspectRatioState.mUserAspectRatio == USER_MIN_ASPECT_RATIO_UNSET
                || mUserAspectRatioState.mUserAspectRatio == USER_MIN_ASPECT_RATIO_FULLSCREEN);
    }

    /**
     * Whether we should enable users to resize the current app.
     */
    boolean shouldEnableUserAspectRatioSettings() {
        // We use mBooleanPropertyAllowUserAspectRatioOverride to allow apps to opt-out which has
        // effect only if explicitly false. If mBooleanPropertyAllowUserAspectRatioOverride is null,
        // the current app doesn't opt-out so the first part of the predicate is true.
        return !mAllowUserAspectRatioOverrideOptProp.isFalse()
                && mAppCompatConfiguration.isUserAppAspectRatioSettingsEnabled()
                && mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest();
    }

    boolean hasFullscreenOverride() {
        // `mUserAspectRatio` is always initialized first in `shouldApplyUserFullscreenOverride()`.
        return shouldApplyUserFullscreenOverride() || isSystemOverrideToFullscreenEnabled();
    }

    float getUserMinAspectRatio() {
        switch (mUserAspectRatioState.mUserAspectRatio) {
            case USER_MIN_ASPECT_RATIO_DISPLAY_SIZE:
                return getDisplaySizeMinAspectRatio();
            case USER_MIN_ASPECT_RATIO_SPLIT_SCREEN:
                return getSplitScreenAspectRatio();
            case USER_MIN_ASPECT_RATIO_16_9:
                return 16 / 9f;
            case USER_MIN_ASPECT_RATIO_4_3:
                return 4 / 3f;
            case USER_MIN_ASPECT_RATIO_3_2:
                return 3 / 2f;
            default:
                throw new AssertionError("Unexpected user min aspect ratio override: "
                        + mUserAspectRatioState.mUserAspectRatio);
        }
    }

    float getSplitScreenAspectRatio() {
        // Getting the same aspect ratio that apps get in split screen.
        final DisplayArea displayArea = mActivityRecord.getDisplayArea();
        if (displayArea == null) {
            return getDefaultMinAspectRatioForUnresizableApps();
        }
        int dividerWindowWidth =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_thickness);
        int dividerInsets =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_insets);
        int dividerSize = dividerWindowWidth - dividerInsets * 2;
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return AppCompatUtils.computeAspectRatio(bounds);
    }

    float getFixedOrientationLetterboxAspectRatio(@NonNull Configuration parentConfiguration) {
        return shouldUseSplitScreenAspectRatio(parentConfiguration)
                ? getSplitScreenAspectRatio()
                : mActivityRecord.shouldCreateAppCompatDisplayInsets()
                        ? getDefaultMinAspectRatioForUnresizableApps()
                        : getDefaultMinAspectRatio();
    }

    float getDefaultMinAspectRatioForUnresizableAppsFromConfig() {
        return mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps();
    }

    boolean isSplitScreenAspectRatioForUnresizableAppsEnabled() {
        return mAppCompatConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled();
    }

    @VisibleForTesting
    float getDisplaySizeMinAspectRatio() {
        final DisplayArea displayArea = mActivityRecord.getDisplayArea();
        if (displayArea == null) {
            return mActivityRecord.info.getMinAspectRatio();
        }
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        return AppCompatUtils.computeAspectRatio(bounds);
    }

    private boolean shouldUseSplitScreenAspectRatio(@NonNull Configuration parentConfiguration) {
        final boolean isBookMode = mAppCompatDeviceStateQuery
                .isDisplayFullScreenAndInPosture(/* isTabletop */false);
        final boolean isNotCenteredHorizontally =
                mAppCompatReachabilityOverrides.getHorizontalPositionMultiplier(parentConfiguration)
                        != LETTERBOX_POSITION_MULTIPLIER_CENTER;
        final boolean isTabletopMode = mAppCompatDeviceStateQuery
                .isDisplayFullScreenAndInPosture(/* isTabletop */ true);
        final boolean isLandscape = isFixedOrientationLandscape(
                mActivityRecord.getOverrideOrientation());
        final AppCompatCameraOverrides cameraOverrides =
                mActivityRecord.mAppCompatController.getAppCompatCameraOverrides();
        final AppCompatCameraPolicy cameraPolicy =
                mActivityRecord.mAppCompatController.getAppCompatCameraPolicy();
        // Don't resize to split screen size when in book mode if letterbox position is centered
        return (isBookMode && isNotCenteredHorizontally || isTabletopMode && isLandscape)
                || cameraOverrides.isCameraCompatSplitScreenAspectRatioAllowed()
                && (cameraPolicy != null
                    && cameraPolicy.isTreatmentEnabledForActivity(mActivityRecord));
    }

    /**
     * Returns the value of the user aspect ratio override property. If unset, return {@code true}.
     */
    boolean getAllowUserAspectRatioOverridePropertyValue() {
        return !mAllowUserAspectRatioOverrideOptProp.isFalse();
    }

    int getUserMinAspectRatioOverrideCode() {
        try {
            return mActivityRecord.mAtmService.getPackageManager()
                    .getUserMinAspectRatio(mActivityRecord.packageName, mActivityRecord.mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception thrown retrieving aspect ratio user override " + this, e);
        }
        return mUserAspectRatioState.mUserAspectRatio;
    }

    private float getDefaultMinAspectRatioForUnresizableApps() {
        if (!mAppCompatConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled()
                || mActivityRecord.getDisplayArea() == null) {
            return mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO
                    ? mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    : getDefaultMinAspectRatio();
        }

        return getSplitScreenAspectRatio();
    }

    float getDefaultMinAspectRatio() {
        if (mActivityRecord.getDisplayArea() == null
                || !mAppCompatConfiguration
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox()) {
            return mAppCompatConfiguration.getFixedOrientationLetterboxAspectRatio();
        }
        return getDisplaySizeMinAspectRatio();
    }

    private static class UserAspectRatioState {
        // TODO(b/315140179): Make mUserAspectRatio final
        // The min aspect ratio override set by user
        @PackageManager.UserMinAspectRatio
        private int mUserAspectRatio = USER_MIN_ASPECT_RATIO_UNSET;
    }

    private Resources getResources() {
        return mActivityRecord.mWmService.mContext.getResources();
    }
}
