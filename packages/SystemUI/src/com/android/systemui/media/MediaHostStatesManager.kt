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

package com.android.systemui.media

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.traceSection
import javax.inject.Inject

/**
 * A class responsible for managing all media host states of the various host locations and
 * coordinating the heights among different players. This class can be used to get the most up to
 * date state for any location.
 */
@SysUISingleton
class MediaHostStatesManager @Inject constructor() {

    private val callbacks: MutableSet<Callback> = mutableSetOf()
    private val controllers: MutableSet<MediaViewController> = mutableSetOf()

    /**
     * The overall sizes of the carousel. This is needed to make sure all players in the carousel
     * have equal size.
     */
    val carouselSizes: MutableMap<Int, MeasurementOutput> = mutableMapOf()

    /**
     * A map with all media states of all locations.
     */
    val mediaHostStates: MutableMap<Int, MediaHostState> = mutableMapOf()

    /**
     * Notify that a media state for a given location has changed. Should only be called from
     * Media hosts themselves.
     */
    fun updateHostState(
        @MediaLocation location: Int,
        hostState: MediaHostState
    ) = traceSection("MediaHostStatesManager#updateHostState") {
        val currentState = mediaHostStates.get(location)
        if (!hostState.equals(currentState)) {
            val newState = hostState.copy()
            mediaHostStates.put(location, newState)
            updateCarouselDimensions(location, hostState)
            // First update all the controllers to ensure they get the chance to measure
            for (controller in controllers) {
                controller.stateCallback.onHostStateChanged(location, newState)
            }

            // Then update all other callbacks which may depend on the controllers above
            for (callback in callbacks) {
                callback.onHostStateChanged(location, newState)
            }
        }
    }

    /**
     * Get the dimensions of all players combined, which determines the overall height of the
     * media carousel and the media hosts.
     */
    fun updateCarouselDimensions(
        @MediaLocation location: Int,
        hostState: MediaHostState
    ): MeasurementOutput = traceSection("MediaHostStatesManager#updateCarouselDimensions") {
        val result = MeasurementOutput(0, 0)
        for (controller in controllers) {
            val measurement = controller.getMeasurementsForState(hostState)
            measurement?.let {
                if (it.measuredHeight > result.measuredHeight) {
                    result.measuredHeight = it.measuredHeight
                }
                if (it.measuredWidth > result.measuredWidth) {
                    result.measuredWidth = it.measuredWidth
                }
            }
        }
        carouselSizes[location] = result
        return result
    }

    /**
     * Add a callback to be called when a MediaState has updated
     */
    fun addCallback(callback: Callback) {
        callbacks.add(callback)
    }

    /**
     * Remove a callback that listens to media states
     */
    fun removeCallback(callback: Callback) {
        callbacks.remove(callback)
    }

    /**
     * Register a controller that listens to media states and is used to determine the size of
     * the media carousel
     */
    fun addController(controller: MediaViewController) {
        controllers.add(controller)
    }

    /**
     * Notify the manager about the removal of a controller.
     */
    fun removeController(controller: MediaViewController) {
        controllers.remove(controller)
    }

    interface Callback {
        /**
         * Notify the callbacks that a media state for a host has changed, and that the
         * corresponding view states should be updated and applied
         */
        fun onHostStateChanged(@MediaLocation location: Int, mediaHostState: MediaHostState)
    }
}
