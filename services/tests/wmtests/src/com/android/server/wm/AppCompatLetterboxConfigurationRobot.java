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

import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;

/**
 * Robot implementation for {@link LetterboxConfiguration}.
 */
class AppCompatLetterboxConfigurationRobot {

    @NonNull
    private final LetterboxConfiguration mLetterboxConfiguration;

    AppCompatLetterboxConfigurationRobot(@NonNull LetterboxConfiguration letterboxConfiguration) {
        mLetterboxConfiguration = letterboxConfiguration;
        spyOn(mLetterboxConfiguration);
    }

    void enableTranslucentPolicy(boolean enabled) {
        when(mLetterboxConfiguration.isTranslucentLetterboxingEnabled()).thenReturn(enabled);
    }

    void enablePolicyForIgnoringRequestedOrientation(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration)
                .isPolicyForIgnoringRequestedOrientationEnabled();
    }

    void enableCameraCompatTreatment(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
    }

    void enableCameraCompatTreatmentAtBuildTime(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();
    }

    void enableUserAppAspectRatioFullscreen(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration).isUserAppAspectRatioFullscreenEnabled();
    }

    void enableUserAppAspectRatioSettings(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration).isUserAppAspectRatioSettingsEnabled();
    }

    void enableCameraCompatSplitScreenAspectRatio(boolean enabled) {
        doReturn(enabled).when(mLetterboxConfiguration)
                .isCameraCompatSplitScreenAspectRatioEnabled();
    }


}
