/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.window;

/**
 * Controls when to show and hide {@link OverlayViewController}(s).
 */
public interface OverlayViewMediator {

    /**
     * Register listeners that could use ContentVisibilityAdjuster to show/hide content.
     *
     * Note that we do not unregister listeners because SystemUI components are expected to live
     * for the lifecycle of the device.
     */
    void registerListeners();

    /**
     * Allows for post-inflation callbacks and listeners to be set inside required {@link
     * OverlayViewController}(s).
     */
    void setupOverlayContentViewControllers();
}
