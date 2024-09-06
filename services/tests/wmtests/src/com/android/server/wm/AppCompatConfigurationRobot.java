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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;

import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;

/**
 * Robot implementation for {@link AppCompatConfiguration}.
 */
class AppCompatConfigurationRobot {

    @NonNull
    private final AppCompatConfiguration mAppCompatConfiguration;

    AppCompatConfigurationRobot(@NonNull AppCompatConfiguration appCompatConfiguration) {
        mAppCompatConfiguration = appCompatConfiguration;
        spyOn(mAppCompatConfiguration);
    }

    void enableTranslucentPolicy(boolean enabled) {
        when(mAppCompatConfiguration.isTranslucentLetterboxingEnabled()).thenReturn(enabled);
    }

    void enablePolicyForIgnoringRequestedOrientation(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
    }

    void enableCameraCompatTreatment(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration).isCameraCompatTreatmentEnabled();
    }

    void enableSplitScreenAspectRatioForUnresizableApps(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration)
                .getIsSplitScreenAspectRatioForUnresizableAppsEnabled();
    }

    void enableCameraCompatTreatmentAtBuildTime(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();
    }

    void enableUserAppAspectRatioFullscreen(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration).isUserAppAspectRatioFullscreenEnabled();
    }

    void enableUserAppAspectRatioSettings(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration).isUserAppAspectRatioSettingsEnabled();
    }

    void enableCameraCompatSplitScreenAspectRatio(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration)
                .isCameraCompatSplitScreenAspectRatioEnabled();
    }

    void enableCompatFakeFocus(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration).isCompatFakeFocusEnabled();
    }

    void enableDisplayAspectRatioEnabledForFixedOrientationLetterbox(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration)
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox();
    }

    void setFixedOrientationLetterboxAspectRatio(float aspectRatio) {
        doReturn(aspectRatio).when(mAppCompatConfiguration)
                .getFixedOrientationLetterboxAspectRatio();
    }

    void setThinLetterboxWidthPx(int thinWidthPx) {
        doReturn(thinWidthPx).when(mAppCompatConfiguration)
                .getThinLetterboxWidthPx();
    }

    void setThinLetterboxHeightPx(int thinHeightPx) {
        doReturn(thinHeightPx).when(mAppCompatConfiguration)
                .getThinLetterboxHeightPx();
    }

    void setLetterboxActivityCornersRounded(boolean rounded) {
        doReturn(rounded).when(mAppCompatConfiguration).isLetterboxActivityCornersRounded();
    }

    void setLetterboxEducationEnabled(boolean enabled) {
        doReturn(enabled).when(mAppCompatConfiguration).getIsEducationEnabled();
    }

    void setLetterboxActivityCornersRadius(int cornerRadius) {
        doReturn(cornerRadius).when(mAppCompatConfiguration).getLetterboxActivityCornersRadius();
    }

    void setLetterboxBackgroundType(@LetterboxBackgroundType int backgroundType) {
        doReturn(backgroundType).when(mAppCompatConfiguration).getLetterboxBackgroundType();
    }

    void setLetterboxBackgroundWallpaperBlurRadiusPx(int blurRadiusPx) {
        doReturn(blurRadiusPx).when(mAppCompatConfiguration)
                .getLetterboxBackgroundWallpaperBlurRadiusPx();
    }

    void setLetterboxBackgroundWallpaperDarkScrimAlpha(float darkScrimAlpha) {
        doReturn(darkScrimAlpha).when(mAppCompatConfiguration)
                .getLetterboxBackgroundWallpaperDarkScrimAlpha();
    }

    void checkToNextLeftStop(boolean invoked) {
        verify(mAppCompatConfiguration, times(invoked ? 1 : 0))
                .movePositionForHorizontalReachabilityToNextLeftStop(anyBoolean());
    }

    void checkToNextRightStop(boolean invoked) {
        verify(mAppCompatConfiguration, times(invoked ? 1 : 0))
                .movePositionForHorizontalReachabilityToNextRightStop(anyBoolean());
    }

    void checkToNextBottomStop(boolean invoked) {
        verify(mAppCompatConfiguration, times(invoked ? 1 : 0))
                .movePositionForVerticalReachabilityToNextBottomStop(anyBoolean());
    }

    void checkToNextTopStop(boolean invoked) {
        verify(mAppCompatConfiguration, times(invoked ? 1 : 0))
                .movePositionForVerticalReachabilityToNextTopStop(anyBoolean());
    }
}
