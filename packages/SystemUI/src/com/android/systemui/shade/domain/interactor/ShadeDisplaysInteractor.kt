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

package com.android.systemui.shade.domain.interactor

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.annotation.UiThread
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.traceSection
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.ShadeWindowLayoutParams
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/** Handles Shade window display change when [ShadeDisplaysRepository.displayId] changes. */
@SysUISingleton
class ShadeDisplaysInteractor
@Inject
constructor(
    optionalShadeRootView: Optional<WindowRootView>,
    private val shadePositionRepository: ShadeDisplaysRepository,
    @ShadeDisplayAware private val shadeContext: Context,
    @ShadeDisplayAware private val wm: WindowManager,
    @Background private val bgScope: CoroutineScope,
    @Main private val mainThreadContext: CoroutineContext,
) : CoreStartable {

    private val shadeLayoutParams: WindowManager.LayoutParams =
        ShadeWindowLayoutParams.create(shadeContext)

    private val shadeRootView =
        optionalShadeRootView.getOrNull()
            ?: error(
                """
            ShadeRootView must be provided for this ShadeDisplayInteractor to work.
            If it is not, it means this is being instantiated in a SystemUI variant that shouldn't.
            """
                    .trimIndent()
            )

    override fun start() {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        bgScope.launchTraced(TAG) {
            shadePositionRepository.displayId.collect { displayId -> moveShadeWindowTo(displayId) }
        }
    }

    /** Tries to move the shade. If anything wrong happens, fails gracefully without crashing. */
    private suspend fun moveShadeWindowTo(destinationId: Int) {
        Log.d(TAG, "Trying to move shade window to display with id $destinationId")
        val currentDisplay = shadeRootView.display
        if (currentDisplay == null) {
            Log.w(TAG, "Current shade display is null")
            return
        }
        val currentId = currentDisplay.displayId
        if (currentId == destinationId) {
            Log.w(TAG, "Trying to move the shade to a display it was already in")
            return
        }
        try {
            withContext(mainThreadContext) { moveShadeWindow(toId = destinationId) }
        } catch (e: IllegalStateException) {
            Log.e(
                TAG,
                "Unable to move the shade window from display $currentId to $destinationId",
                e,
            )
        }
    }

    @UiThread
    private fun moveShadeWindow(toId: Int) {
        traceSection({ "moveShadeWindow  to $toId" }) {
            removeShadeWindow()
            updateContextDisplay(toId)
            addShadeWindow()
        }
    }

    @UiThread
    private fun removeShadeWindow(): Unit =
        traceSection("removeShadeWindow") { wm.removeView(shadeRootView) }

    @UiThread
    private fun addShadeWindow(): Unit =
        traceSection("addShadeWindow") { wm.addView(shadeRootView, shadeLayoutParams) }

    @UiThread
    private fun updateContextDisplay(newDisplayId: Int) {
        traceSection("updateContextDisplay") { shadeContext.updateDisplay(newDisplayId) }
    }

    private companion object {
        const val TAG = "ShadeDisplaysInteractor"
    }
}
