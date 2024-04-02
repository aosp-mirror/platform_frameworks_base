/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade.transition

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.PanelState
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.phone.ScrimController
import java.io.PrintWriter
import javax.inject.Inject

/** Controls the scrim properties during the shade expansion transition on non-lockscreen. */
@SysUISingleton
class ScrimShadeTransitionController
@Inject
constructor(
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    private val dumpManager: DumpManager,
    private val scrimController: ScrimController,
) {
    private var lastExpansionFraction: Float? = null
    private var lastExpansionEvent: ShadeExpansionChangeEvent? = null
    private var currentPanelState: Int? = null

    fun init() {
        val currentState =
            shadeExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged)
        onPanelExpansionChanged(currentState)
        shadeExpansionStateManager.addStateListener(this::onPanelStateChanged)
        dumpManager.registerDumpable(
            ScrimShadeTransitionController::class.java.simpleName,
            this::dump
        )
    }

    private fun onPanelStateChanged(@PanelState state: Int) {
        currentPanelState = state
        onStateChanged()
    }

    fun onPanelExpansionChanged(shadeExpansionChangeEvent: ShadeExpansionChangeEvent) {
        lastExpansionEvent = shadeExpansionChangeEvent
        onStateChanged()
    }

    private fun onStateChanged() {
        val expansionEvent = lastExpansionEvent ?: return
        val expansionFraction = calculateScrimExpansionFraction(expansionEvent)
        scrimController.setRawPanelExpansionFraction(expansionFraction)
        lastExpansionFraction = expansionFraction
    }

    private fun calculateScrimExpansionFraction(expansionEvent: ShadeExpansionChangeEvent): Float {
        return expansionEvent.fraction
    }

    private fun dump(printWriter: PrintWriter, args: Array<String>) {
        printWriter.println(
            """
                ScrimShadeTransitionController:
                  State:
                    currentPanelState: $currentPanelState
                    lastExpansionFraction: $lastExpansionFraction
                    lastExpansionEvent: $lastExpansionEvent
            """
                .trimIndent()
        )
    }
}
