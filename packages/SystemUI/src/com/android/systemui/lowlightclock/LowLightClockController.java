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

package com.android.systemui.lowlightclock;

import android.view.ViewGroup;

/**
 * A controller responsible for attaching and showing an optional low-light clock while dozing.
 */
public interface LowLightClockController {
    /**
     * Returns {@code true} if the low-light clock is enabled.
     */
    boolean isLowLightClockEnabled();

    /**
     * Attach the low light-clock to the given parent {@link ViewGroup}.
     * @param parent The parent {@link ViewGroup} to which the low-light clock view should be
     *               attached.
     */
    void attachLowLightClockView(ViewGroup parent);

    /**
     * Show or hide the low-light clock.
     * @param show Whether to show the low-light clock.
     * @return {@code true} if the low-light clock was shown.
     */
    boolean showLowLightClock(boolean show);

    /**
     * An opportunity to perform burn-in prevention.
     */
    void dozeTimeTick();
}
