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

import android.content.ComponentCallbacks
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.view.WindowManager
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
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.phone.ConfigurationForwarder
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
    @ShadeDisplayAware private val shadeResources: Resources,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    @Background private val bgScope: CoroutineScope,
    @ShadeDisplayAware private val shadeConfigurationForwarder: ConfigurationForwarder,
    @Main private val mainThreadContext: CoroutineContext,
) : CoreStartable {

    private val shadeRootView =
        optionalShadeRootView.getOrNull()
            ?: error(
                """
            ShadeRootView must be provided for this ShadeDisplayInteractor to work.
            If it is not, it means this is being instantiated in a SystemUI variant that shouldn't.
            """
                    .trimIndent()
            )
    // TODO: b/362719719 - Get rid of this callback as the root view should automatically get the
    //  correct configuration once it's moved to another window.
    private var unregisterConfigChangedCallbacks: (() -> Unit)? = null

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
            moveShadeWindow(fromId = currentId, toId = destinationId)
        } catch (e: IllegalStateException) {
            Log.e(
                TAG,
                "Unable to move the shade window from display $currentId to $destinationId",
                e,
            )
        }
    }

    private suspend fun moveShadeWindow(fromId: Int, toId: Int) {
        val (_, _, _, sourceWm) = getDisplayWindowProperties(fromId)
        val (_, _, destContext, destWm) = getDisplayWindowProperties(toId)
        withContext(mainThreadContext) {
            traceSection({ "MovingShadeWindow from $fromId to $toId" }) {
                removeShade(sourceWm)
                addShade(destWm)
                overrideContextAndResources(newContext = destContext)
                registerConfigurationChange(destContext)
            }
            traceSection("ShadeDisplaysInteractor#onConfigurationChanged") {
                dispatchConfigurationChanged(destContext.resources.configuration)
            }
        }
    }

    private fun removeShade(wm: WindowManager): Unit =
        traceSection("removeView") { wm.removeView(shadeRootView) }

    private fun addShade(wm: WindowManager): Unit =
        traceSection("addView") {
            wm.addView(shadeRootView, ShadeWindowLayoutParams.create(shadeContext))
        }

    private fun overrideContextAndResources(newContext: Context) {
        val contextWrapper =
            shadeContext as? MutableContextWrapper
                ?: error("Shade context is not a MutableContextWrapper!")
        contextWrapper.baseContext = newContext
        // Override needed in case someone is keeping a reference to the resources from the old
        // context.
        // TODO: b/362719719 - This shouldn't be needed, as resources should be updated when the
        //  window is moved to the new display automatically.
        shadeResources.impl = shadeContext.resources.impl
    }

    private fun dispatchConfigurationChanged(newConfig: Configuration) {
        shadeConfigurationForwarder.onConfigurationChanged(newConfig)
        shadeRootView.dispatchConfigurationChanged(newConfig)
        shadeRootView.requestLayout()
    }

    private fun registerConfigurationChange(context: Context) {
        // we should keep only one at the time.
        unregisterConfigChangedCallbacks?.invoke()
        val callback =
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    dispatchConfigurationChanged(newConfig)
                }

                override fun onLowMemory() {}
            }
        context.registerComponentCallbacks(callback)
        unregisterConfigChangedCallbacks = {
            context.unregisterComponentCallbacks(callback)
            unregisterConfigChangedCallbacks = null
        }
    }

    private fun getDisplayWindowProperties(displayId: Int): DisplayWindowProperties {
        return displayWindowPropertiesRepository.get(displayId, TYPE_NOTIFICATION_SHADE)
    }

    private companion object {
        const val TAG = "ShadeDisplaysInteractor"
    }
}
