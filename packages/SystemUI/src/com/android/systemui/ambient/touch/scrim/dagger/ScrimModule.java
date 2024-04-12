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

package com.android.systemui.ambient.touch.scrim.dagger;

import com.android.systemui.ambient.touch.scrim.BouncerScrimController;
import com.android.systemui.ambient.touch.scrim.BouncerlessScrimController;
import com.android.systemui.ambient.touch.scrim.ScrimController;

import dagger.Module;
import dagger.Provides;

import javax.inject.Named;

/**
 * Module for scrim related dependencies.
 */
@Module
public interface ScrimModule {
    String BOUNCERLESS_SCRIM_CONTROLLER = "bouncerless_scrim_controller";
    String BOUNCER_SCRIM_CONTROLLER = "bouncer_scrim_controller";

    /** */
    @Provides
    @Named(BOUNCERLESS_SCRIM_CONTROLLER)
    static ScrimController providesBouncerlessScrimController(
            BouncerlessScrimController controller) {
        return controller;
    }

    /** */
    @Provides
    @Named(BOUNCER_SCRIM_CONTROLLER)
    static ScrimController providesBouncerScrimController(
            BouncerScrimController controller) {
        return controller;
    }
}
