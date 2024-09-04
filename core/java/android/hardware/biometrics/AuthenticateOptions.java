/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Common authentication options that are exposed across all modalities.
 *
 * @hide
 */
public interface AuthenticateOptions  {

    /** The user id for this operation. */
    int getUserId();

    /** The sensor id for this operation. */
    int getSensorId();

    /** The state is unknown. */
    int DISPLAY_STATE_UNKNOWN = 0;

    /** The display is on and showing the lockscreen (or an occluding app). */
    int DISPLAY_STATE_LOCKSCREEN = 1;

    /** The display is off or dozing. */
    int DISPLAY_STATE_NO_UI = 2;

    /** The display is showing a screensaver (dreaming). */
    int DISPLAY_STATE_SCREENSAVER = 3;

    /** The display is dreaming with always on display. */
    int DISPLAY_STATE_AOD = 4;

    /** The doze state of the device. */
    @IntDef(prefix = "DISPLAY_STATE_", value = {
            DISPLAY_STATE_UNKNOWN,
            DISPLAY_STATE_LOCKSCREEN,
            DISPLAY_STATE_NO_UI,
            DISPLAY_STATE_SCREENSAVER,
            DISPLAY_STATE_AOD
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DisplayState {}

    /** The current doze state of the device. */
    @DisplayState
    int getDisplayState();

    /**
     * The package name for that operation that should be used for
     * {@link android.app.AppOpsManager} verification.
     */
    @NonNull String getOpPackageName();

    /** The attribution tag, if any. */
    @Nullable String getAttributionTag();

    /** If the authentication is requested due to mandatory biometrics being active. */
    boolean isMandatoryBiometrics();
}
