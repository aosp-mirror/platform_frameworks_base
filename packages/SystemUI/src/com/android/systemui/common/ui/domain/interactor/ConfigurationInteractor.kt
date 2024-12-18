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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.common.ui.domain.interactor

import android.content.res.Configuration
import android.graphics.Rect
import android.view.Surface
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** Business logic related to configuration changes. */
@SysUISingleton
class ConfigurationInteractor @Inject constructor(private val repository: ConfigurationRepository) {
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

    /** Returns the unadjusted screen size. */
    val maxBounds: Flow<Rect> =
        repository.configurationValues
            .map { Rect(it.windowConfiguration.maxBounds) }
            .distinctUntilChanged()

    /**
     * Returns screen size adjusted to rotation, so returned screen sizes are stable across all
     * rotations, could be useful if you need to react to screen resize (e.g. fold/unfold on
     * foldable devices)
     */
    val naturalMaxBounds: Flow<Rect> =
        repository.configurationValues.map { it.naturalScreenBounds }.distinctUntilChanged()

    /**
     * The layout direction. Will be either `View#LAYOUT_DIRECTION_LTR` or
     * `View#LAYOUT_DIRECTION_RTL`.
     */
    val layoutDirection: Flow<Int> =
        repository.configurationValues.map { it.layoutDirection }.distinctUntilChanged()

    /** Given [resourceId], emit the dimension pixel size on config change */
    fun dimensionPixelSize(resourceId: Int): Flow<Int> {
        return onAnyConfigurationChange.mapLatest { repository.getDimensionPixelSize(resourceId) }
    }

    /** Emits the dimensional pixel size of the given resource, inverting it for RTL if necessary */
    fun directionalDimensionPixelSize(originLayoutDirection: Int, resourceId: Int): Flow<Int> {
        return dimensionPixelSize(resourceId).combine(layoutDirection) { size, direction ->
            if (originLayoutDirection == direction) size else -size
        }
    }

    /** Given a set of [resourceId]s, emit Map<ResourceId, DimensionPixelSize> on config change */
    fun dimensionPixelSize(resourceIds: Set<Int>): Flow<Map<Int, Int>> {
        return onAnyConfigurationChange.mapLatest {
            resourceIds.associateWith { repository.getDimensionPixelSize(it) }
        }
    }

    /** Emit an event on any config change */
    val onAnyConfigurationChange: Flow<Unit> =
        repository.onAnyConfigurationChange.onStart { emit(Unit) }

    /** Emits the new configuration on any configuration change */
    val configurationValues: Flow<Configuration> = repository.configurationValues

    /** Emits the current resolution scaling factor */
    val scaleForResolution: Flow<Float> = repository.scaleForResolution
}
