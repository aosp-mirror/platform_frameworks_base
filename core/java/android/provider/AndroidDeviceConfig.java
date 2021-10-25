/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider;

import android.annotation.TestApi;

/**
 * Interface for accessing keys belonging to {@link DeviceConfig#NAMESPACE_ANDROID}.
 * @hide
 */
@TestApi
public interface AndroidDeviceConfig {

    /**
     * Key for accessing the system gesture exclusion limit (an integer in dp).
     *
     * <p>Note: On Devices running Q, this key is in the "android:window_manager" namespace.
     *
     * @see android.provider.DeviceConfig#NAMESPACE_ANDROID
     */
    String KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP = "system_gesture_exclusion_limit_dp";

    /**
     * Key for controlling whether system gestures are implicitly excluded by windows requesting
     * sticky immersive mode from apps that are targeting an SDK prior to Q.
     *
     * <p>Note: On Devices running Q, this key is in the "android:window_manager" namespace.
     *
     * @see android.provider.DeviceConfig#NAMESPACE_ANDROID
     */
    String KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE =
            "system_gestures_excluded_by_pre_q_sticky_immersive";

}
