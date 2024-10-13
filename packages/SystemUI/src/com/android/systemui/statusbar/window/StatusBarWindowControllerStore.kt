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

package com.android.systemui.statusbar.window

import android.view.Display
import android.view.WindowManager
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Store that allows to retrieve per display instances of [StatusBarWindowController]. */
interface StatusBarWindowControllerStore {
    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    val defaultDisplay: StatusBarWindowController

    /**
     * Returns an instance for a specific display id.
     *
     * @throws IllegalArgumentException if [displayId] doesn't match the id of any existing
     *   displays.
     */
    fun forDisplay(displayId: Int): StatusBarWindowController
}

@SysUISingleton
class MultiDisplayStatusBarWindowControllerStore
@Inject
constructor(
    @Background private val backgroundApplicationScope: CoroutineScope,
    private val controllerFactory: StatusBarWindowController.Factory,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val viewCaptureAwareWindowManagerFactory: ViewCaptureAwareWindowManager.Factory,
    private val displayRepository: DisplayRepository,
) : StatusBarWindowControllerStore, CoreStartable {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    private val perDisplayControllers = ConcurrentHashMap<Int, StatusBarWindowController>()

    override fun start() {
        backgroundApplicationScope.launch(CoroutineName("StatusBarWindowController#start")) {
            displayRepository.displayRemovalEvent.collect { displayId ->
                perDisplayControllers.remove(displayId)
            }
        }
    }

    override val defaultDisplay: StatusBarWindowController
        get() = forDisplay(Display.DEFAULT_DISPLAY)

    override fun forDisplay(displayId: Int): StatusBarWindowController {
        if (displayRepository.getDisplay(displayId) == null) {
            throw IllegalArgumentException("Display with id $displayId doesn't exist.")
        }
        return perDisplayControllers.computeIfAbsent(displayId) {
            createControllerForDisplay(displayId)
        }
    }

    private fun createControllerForDisplay(displayId: Int): StatusBarWindowController {
        val statusBarDisplayContext =
            displayWindowPropertiesRepository.get(
                displayId = displayId,
                windowType = WindowManager.LayoutParams.TYPE_STATUS_BAR,
            )
        val viewCaptureAwareWindowManager =
            viewCaptureAwareWindowManagerFactory.create(statusBarDisplayContext.windowManager)
        return controllerFactory.create(
            statusBarDisplayContext.context,
            viewCaptureAwareWindowManager,
        )
    }
}

@SysUISingleton
class SingleDisplayStatusBarWindowControllerStore
@Inject
constructor(private val controller: StatusBarWindowController) : StatusBarWindowControllerStore {

    init {
        StatusBarConnectedDisplays.assertInLegacyMode()
    }

    override val defaultDisplay = controller

    override fun forDisplay(displayId: Int) = controller
}
