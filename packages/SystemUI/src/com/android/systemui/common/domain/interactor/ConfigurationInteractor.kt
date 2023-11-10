/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.common.domain.interactor

import android.content.res.Configuration
import android.graphics.Rect
import android.view.Surface
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface ConfigurationInteractor {
    /**
     * Returns screen size adjusted to rotation, so returned screen sizes are stable across all
     * rotations, could be useful if you need to react to screen resize (e.g. fold/unfold on
     * foldable devices)
     */
    val naturalMaxBounds: Flow<Rect>
}

class ConfigurationInteractorImpl
@Inject
constructor(private val repository: ConfigurationRepository) : ConfigurationInteractor {

    override val naturalMaxBounds: Flow<Rect>
        get() = repository.configurationValues.map { it.naturalScreenBounds }.distinctUntilChanged()

    /**
     * Returns screen size adjusted to rotation, so returned screen size is stable across all
     * rotations
     */
    private val Configuration.naturalScreenBounds: Rect
        get() {
            val rotation = windowConfiguration.displayRotation
            val maxBounds = windowConfiguration.maxBounds
            return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                Rect(0, 0, maxBounds.width(), maxBounds.height())
            } else {
                Rect(0, 0, maxBounds.height(), maxBounds.width())
            }
        }
}
