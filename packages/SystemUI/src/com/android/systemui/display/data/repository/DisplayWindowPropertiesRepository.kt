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

import android.annotation.SuppressLint
import android.content.Context
import android.view.Display
import android.view.LayoutInflater
import android.view.WindowManager
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.shared.model.DisplayWindowProperties
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [DisplayWindowProperties]. */
interface DisplayWindowPropertiesRepository {

    /**
     * Returns a [DisplayWindowProperties] instance for a given display id and window type.
     *
     * @throws IllegalArgumentException if no display with the given display id exists.
     */
    fun get(
        displayId: Int,
        @WindowManager.LayoutParams.WindowType windowType: Int,
    ): DisplayWindowProperties
}

@SysUISingleton
class DisplayWindowPropertiesRepositoryImpl
@Inject
constructor(
    @Background private val backgroundApplicationScope: CoroutineScope,
    private val globalContext: Context,
    private val globalWindowManager: WindowManager,
    private val globalLayoutInflater: LayoutInflater,
    private val displayRepository: DisplayRepository,
) : DisplayWindowPropertiesRepository, CoreStartable {

    init {
        check(StatusBarConnectedDisplays.isEnabled || ShadeWindowGoesAround.isEnabled) {
            "This should be instantiated only when wither StatusBarConnectedDisplays or " +
                "ShadeWindowGoesAround are enabled."
        }
    }

    private val properties: Table<Int, Int, DisplayWindowProperties> = HashBasedTable.create()

    override fun get(
        displayId: Int,
        @WindowManager.LayoutParams.WindowType windowType: Int,
    ): DisplayWindowProperties {
        val display =
            displayRepository.getDisplay(displayId)
                ?: throw IllegalArgumentException("Display with id $displayId doesn't exist")
        return properties.get(displayId, windowType)
            ?: create(display, windowType).also { properties.put(displayId, windowType, it) }
    }

    override fun start() {
        backgroundApplicationScope.launch("DisplayWindowPropertiesRepositoryImpl#start") {
            displayRepository.displayRemovalEvent.collect { removedDisplayId ->
                properties.row(removedDisplayId).clear()
            }
        }
    }

    private fun create(display: Display, windowType: Int): DisplayWindowProperties {
        val displayId = display.displayId
        return if (displayId == Display.DEFAULT_DISPLAY) {
            // For the default display, we can just reuse the global/application properties.
            // Creating a window context is expensive, therefore we avoid it.
            DisplayWindowProperties(
                displayId = displayId,
                windowType = windowType,
                context = globalContext,
                windowManager = globalWindowManager,
                layoutInflater = globalLayoutInflater,
            )
        } else {
            val context = createWindowContext(display, windowType)
            @SuppressLint("NonInjectedService") // Need to manually get the service
            val windowManager = context.getSystemService(WindowManager::class.java) as WindowManager
            val layoutInflater = LayoutInflater.from(context)
            DisplayWindowProperties(displayId, windowType, context, windowManager, layoutInflater)
        }
    }

    private fun createWindowContext(display: Display, windowType: Int): Context =
        globalContext.createWindowContext(display, windowType, /* options= */ null).also {
            it.setTheme(R.style.Theme_SystemUI)
        }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.write("perDisplayContexts: $properties")
    }
}
