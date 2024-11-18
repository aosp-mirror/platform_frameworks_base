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

package com.android.systemui.display.data.repository

import android.view.Display
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/** Provides per display instances of [T]. */
interface PerDisplayStore<T> {

    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    val defaultDisplay: T

    /**
     * Returns an instance for a specific display id.
     *
     * @throws IllegalArgumentException if [displayId] doesn't match the id of any existing
     *   displays.
     */
    fun forDisplay(displayId: Int): T
}

abstract class PerDisplayStoreImpl<T>(
    @Background private val backgroundApplicationScope: CoroutineScope,
    private val displayRepository: DisplayRepository,
) : PerDisplayStore<T> {

    private val perDisplayInstances = ConcurrentHashMap<Int, T>()

    private val coroutineScope by lazy {
        CoroutineScope(
            backgroundApplicationScope.coroutineContext +
                newTracingContext("PerDisplayStore<${instanceClass.simpleName}>")
        )
    }

    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    override val defaultDisplay: T
        get() = forDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Returns an instance for a specific display id.
     *
     * @throws IllegalArgumentException if [displayId] doesn't match the id of any existing
     *   displays.
     */
    override fun forDisplay(displayId: Int): T {
        if (displayRepository.getDisplay(displayId) == null) {
            throw IllegalArgumentException("Display with id $displayId doesn't exist.")
        }
        return perDisplayInstances.computeIfAbsent(displayId) {
            listenForDisplayRemoval(displayId)
            createInstanceForDisplay(displayId)
        }
    }

    protected abstract fun createInstanceForDisplay(displayId: Int): T

    private fun listenForDisplayRemoval(displayId: Int) {
        coroutineScope.launch("listenForDisplayRemoval($displayId)") {
            displayRepository.displayRemovalEvent.first { it == displayId }
            val removedInstance = perDisplayInstances.remove(displayId)
            removedInstance?.let { onDisplayRemovalAction(it) }
        }
    }

    abstract val instanceClass: Class<T>

    /**
     * Will be called when the display associated with [instance] was removed. It allows to perform
     * any clean up if needed.
     */
    open suspend fun onDisplayRemovalAction(instance: T) {}
}

class SingleDisplayStore<T>(defaultInstance: T) : PerDisplayStore<T> {
    override val defaultDisplay: T = defaultInstance

    override fun forDisplay(displayId: Int): T = defaultDisplay
}
