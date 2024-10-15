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

package com.android.systemui.statusbar.core

import android.view.Display
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Provides per display instances of [StatusBarInitializer]. */
interface StatusBarInitializerStore {
    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    val defaultDisplay: StatusBarInitializer

    /**
     * Returns an instance for a specific display id.
     *
     * @throws IllegalArgumentException if [displayId] doesn't match the id of any existing
     *   displays.
     */
    fun forDisplay(displayId: Int): StatusBarInitializer
}

@SysUISingleton
class MultiDisplayStatusBarInitializerStore
@Inject
constructor(
    @Background private val backgroundApplicationScope: CoroutineScope,
    private val factory: StatusBarInitializer.Factory,
    private val displayRepository: DisplayRepository,
) : StatusBarInitializerStore, CoreStartable {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    private val perDisplayInitializers = ConcurrentHashMap<Int, StatusBarInitializer>()

    override val defaultDisplay: StatusBarInitializer
        get() = forDisplay(Display.DEFAULT_DISPLAY)

    override fun forDisplay(displayId: Int): StatusBarInitializer {
        if (displayRepository.getDisplay(displayId) == null) {
            throw IllegalArgumentException("Display with id $displayId doesn't exist.")
        }
        return perDisplayInitializers.computeIfAbsent(displayId) { factory.create(displayId) }
    }

    override fun start() {
        backgroundApplicationScope.launch(
            CoroutineName("MultiDisplayStatusBarInitializerStore#start")
        ) {
            displayRepository.displayRemovalEvent.collect { removedDisplayId ->
                perDisplayInitializers.remove(removedDisplayId)
            }
        }
    }
}

@SysUISingleton
class SingleDisplayStatusBarInitializerStore
@Inject
constructor(factory: StatusBarInitializerImpl.Factory) : StatusBarInitializerStore {

    init {
        StatusBarConnectedDisplays.assertInLegacyMode()
    }

    private val defaultInstance = factory.create(Display.DEFAULT_DISPLAY)

    override val defaultDisplay: StatusBarInitializer = defaultInstance

    override fun forDisplay(displayId: Int): StatusBarInitializer = defaultDisplay
}
