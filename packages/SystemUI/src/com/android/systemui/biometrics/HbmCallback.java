/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.Nullable;
import android.view.Surface;

import com.android.systemui.biometrics.HbmTypes.HbmType;

/**
 * Interface for controlling the high-brightness mode (HBM). UdfpsView can use this callback to
 * enable the HBM while showing the fingerprint illumination, and to disable the HBM after the
 * illumination is no longer necessary.
 */
public interface HbmCallback {
    /**
     * UdfpsView will call this to enable the HBM when the fingerprint illumination is needed.
     *
     * @param hbmType The type of HBM that should be enabled. See {@link HbmTypes}.
     * @param surface The surface for which the HBM is requested, in case the HBM implementation
     *                needs to set special surface flags to enable the HBM. Can be null.
     */
    void enableHbm(@HbmType int hbmType, @Nullable Surface surface);

    /**
     * UdfpsView will call this to disable the HBM when the illumination is not longer needed.
     *
     * @param hbmType The type of HBM that should be disabled. See {@link HbmTypes}.
     * @param surface The surface for which the HBM is requested, in case the HBM implementation
     *                needs to unset special surface flags to disable the HBM. Can be null.
     */
    void disableHbm(@HbmType int hbmType, @Nullable Surface surface);
}
