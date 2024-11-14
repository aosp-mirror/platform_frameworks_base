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
import android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.traceSection
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.shared.model.DisplayWindowProperties
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.ShadeWindowLayoutParams
import com.android.systemui.shade.data.repository.ShadePositionRepository
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.phone.ConfigurationForwarder
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/** Handles Shade window display change when [ShadePositionRepository.displayId] changes. */
@SysUISingleton
class ShadeDisplaysInteractor
@Inject
constructor(
    private val shadeRootView: WindowRootView,
    private val shadePositionRepository: ShadePositionRepository,
    @ShadeDisplayAware private val shadeContext: Context,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    @Background private val bgScope: CoroutineScope,
    @ShadeDisplayAware private val configurationForwarder: ConfigurationForwarder,
    @Main private val mainContext: CoroutineContext,
) : CoreStartable {

    override fun start() {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        bgScope.launchTraced(TAG) {
            shadePositionRepository.displayId.collect { displayId -> moveShadeWindowTo(displayId) }
        }
    }

    /** Tries to move the shade. If anything wrong happens, fails gracefully without crashing. */
    private suspend fun moveShadeWindowTo(destinationDisplayId: Int) {
        val currentId = shadeRootView.display.displayId
        if (currentId == destinationDisplayId) {
            Log.w(TAG, "Trying to move the shade to a display it was already in")
            return
        }
        try {
            moveShadeWindow(fromId = currentId, toId = destinationDisplayId)
        } catch (e: IllegalStateException) {
            Log.e(
                TAG,
                "Unable to move the shade window from display $currentId to $destinationDisplayId",
                e,
            )
        }
    }

    private suspend fun moveShadeWindow(fromId: Int, toId: Int) {
        val sourceProperties = getDisplayWindowProperties(fromId)
        val destinationProperties = getDisplayWindowProperties(toId)
        traceSection({ "MovingShadeWindow from $fromId to $toId" }) {
            withContext(mainContext) {
                traceSection("removeView") {
                    sourceProperties.windowManager.removeView(shadeRootView)
                }
                traceSection("addView") {
                    destinationProperties.windowManager.addView(
                        shadeRootView,
                        ShadeWindowLayoutParams.create(shadeContext),
                    )
                }
            }
        }
        traceSection("SecondaryShadeInteractor#onConfigurationChanged") {
            configurationForwarder.onConfigurationChanged(
                destinationProperties.context.resources.configuration
            )
        }
    }

    private fun getDisplayWindowProperties(displayId: Int): DisplayWindowProperties {
        return displayWindowPropertiesRepository.get(displayId, TYPE_NOTIFICATION_SHADE)
    }

    private companion object {
        const val TAG = "SecondaryShadeInteractor"
    }
}
