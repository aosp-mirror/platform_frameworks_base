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

package com.android.systemui.controls.dagger

import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pseudo-component to inject into classes outside `com.android.systemui.controls`.
 *
 * If `featureEnabled` is false, all the optionals should be empty. The controllers will only be
 * instantiated if `featureEnabled` is true.
 */
@Singleton
class ControlsComponent @Inject constructor(
    @ControlsFeatureEnabled private val featureEnabled: Boolean,
    private val lazyControlsController: Lazy<ControlsController>,
    private val lazyControlsUiController: Lazy<ControlsUiController>,
    private val lazyControlsListingController: Lazy<ControlsListingController>
) {
    fun getControlsController(): Optional<ControlsController> {
        return if (featureEnabled) Optional.of(lazyControlsController.get()) else Optional.empty()
    }

    fun getControlsUiController(): Optional<ControlsUiController> {
        return if (featureEnabled) Optional.of(lazyControlsUiController.get()) else Optional.empty()
    }

    fun getControlsListingController(): Optional<ControlsListingController> {
        return if (featureEnabled) {
            Optional.of(lazyControlsListingController.get())
        } else {
            Optional.empty()
        }
    }
}