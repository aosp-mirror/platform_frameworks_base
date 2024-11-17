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

package com.android.systemui.shade.data.repository

import android.view.Display
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadePrimaryDisplayCommand
import com.android.systemui.statusbar.commandline.CommandRegistry
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ShadeDisplaysRepository {
    /** ID of the display which currently hosts the shade */
    val displayId: StateFlow<Int>

    /**
     * Updates the value of the shade display id stored, emitting to the new display id to every
     * component dependent on the shade display id
     */
    fun setDisplayId(displayId: Int)

    /** Resets value of shade primary display to the default display */
    fun resetDisplayId()
}

/** Source of truth for the display currently holding the shade. */
@SysUISingleton
class ShadeDisplaysRepositoryImpl
@Inject
constructor(private val commandRegistry: CommandRegistry) : ShadeDisplaysRepository, CoreStartable {
    private val _displayId = MutableStateFlow(Display.DEFAULT_DISPLAY)

    override val displayId: StateFlow<Int>
        get() = _displayId

    override fun setDisplayId(displayId: Int) {
        _displayId.value = displayId
    }

    override fun resetDisplayId() {
        _displayId.value = Display.DEFAULT_DISPLAY
    }

    override fun start() {
        commandRegistry.registerCommand("shade_display_override") {
            ShadePrimaryDisplayCommand(this)
        }
    }
}
