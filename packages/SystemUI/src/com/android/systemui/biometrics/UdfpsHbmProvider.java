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

import com.android.systemui.biometrics.UdfpsHbmTypes.HbmType;

/**
 * Interface for controlling the high-brightness mode (HBM). UdfpsView can use this callback to
 * enable the HBM while showing the fingerprint illumination, and to disable the HBM after the
 * illumination is no longer necessary.
 */
public interface UdfpsHbmProvider {

    /**
     * UdfpsView will call this to enable the HBM when the fingerprint illumination is needed.
     *
     * This method is a no-op when some type of HBM is already enabled.
     *
     * This method must be called from the UI thread. The callback, if provided, will also be
     * invoked from the UI thread.
     *
     * @param hbmType The type of HBM that should be enabled. See {@link UdfpsHbmTypes}.
     * @param surface The surface for which the HBM is requested, in case the HBM implementation
     *                needs to set special surface flags to enable the HBM. Can be null.
     * @param onHbmEnabled A runnable that will be executed once HBM is enabled.
     */
    void enableHbm(@HbmType int hbmType, @Nullable Surface surface,
            @Nullable Runnable onHbmEnabled);

    /**
     * UdfpsView will call this to disable the HBM when the illumination is not longer needed.
     *
     * This method is a no-op when HBM is already disabled. If HBM is enabled, this method will
     * disable HBM for the {@code hbmType} and {@code surface} that were provided to the
     * corresponding {@link #enableHbm(int, Surface, Runnable)}.
     *
     * The call must be made from the UI thread. The callback, if provided, will also be invoked
     * from the UI thread.
     *
     * @param onHbmDisabled A runnable that will be executed once HBM is disabled.
     */
    void disableHbm(@Nullable Runnable onHbmDisabled);
}
