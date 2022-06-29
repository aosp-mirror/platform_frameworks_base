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
     * @param onHbmEnabled A runnable that will be executed once HBM is enabled.
     *
     * TODO(b/231335067): enableHbm with halControlsIllumination=true shouldn't make sense.
     *     This only makes sense now because vendor code may rely on the side effects of enableHbm.
     */
    void enableHbm(boolean halControlsIllumination, @Nullable Runnable onHbmEnabled);

    /**
     * UdfpsView will call this to disable HBM when illumination is no longer needed.
     *
     * This method will disable HBM if HBM is enabled. Otherwise, if HBM is already disabled,
     * this method is a no-op.
     *
     * The call must be made from the UI thread. The callback, if provided, will also be invoked
     * from the UI thread.
     *
     * @param onHbmDisabled A runnable that will be executed once HBM is disabled.
     */
    void disableHbm(@Nullable Runnable onHbmDisabled);
}
