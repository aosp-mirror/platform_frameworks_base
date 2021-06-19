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
 * Interface that should be implemented by UI's that need to coordinate user touches,
 * views/animations, and modules that start/stop display illumination.
 */
interface UdfpsIlluminator {
    /**
     * @param hbmProvider Invoked when HBM should be enabled or disabled.
     */
    void setHbmProvider(@Nullable UdfpsHbmProvider hbmProvider);

    /**
     * Invoked when illumination should start.
     * @param onIlluminatedRunnable Invoked when the display has been illuminated.
     */
    void startIllumination(@Nullable Runnable onIlluminatedRunnable);

    /**
     * Invoked when illumination should end.
     */
    void stopIllumination();
}
