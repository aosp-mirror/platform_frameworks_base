/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * Interface for toggling the optimal display mode for the under-display fingerprint sensor
 * (UDFPS). For example, the implementation might change the refresh rate and activate a
 * high-brightness mode.
 */
public interface UdfpsDisplayModeProvider {

    /**
     * Enables the optimal display mode for UDFPS. The mode will persist until
     * {@link #disable(Runnable)} is called.
     *
     * This call must be made from the UI thread. The callback, if provided, will also be invoked
     * from the UI thread.
     *
     * @param onEnabled A runnable that will be executed once the mode is enabled.
     */
    void enable(@Nullable Runnable onEnabled);

    /**
     * Disables the mode that was enabled by {@link #enable(Runnable)}.
     *
     * The call must be made from the UI thread. The callback, if provided, will also be invoked
     * from the UI thread.
     *
     * @param onDisabled A runnable that will be executed once mode is disabled.
     */
    void disable(@Nullable Runnable onDisabled);
}
