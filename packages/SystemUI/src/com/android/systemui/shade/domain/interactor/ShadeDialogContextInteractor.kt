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
import android.view.Display
import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.traceSection
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/** Provides the correct context to show dialogs on the shade window, whenever it moves. */
interface ShadeDialogContextInteractor {
    /** Context usable to create dialogs on the notification shade display. */
    val context: Context
}

@SysUISingleton
class ShadeDialogContextInteractorImpl
@Inject
constructor(
    @Main private val defaultContext: Context,
    private val displayWindowPropertyRepository: Provider<DisplayWindowPropertiesRepository>,
    private val shadeDisplaysRepository: ShadeDisplaysRepository,
    @Background private val bgScope: CoroutineScope,
) : CoreStartable, ShadeDialogContextInteractor {

    override fun start() {
        if (ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()) return
        bgScope.launchTraced(TAG) {
            shadeDisplaysRepository.displayId
                // No need for default display pre-warming.
                .filter { it != Display.DEFAULT_DISPLAY }
                .collectLatest { displayId ->
                    // Prewarms the context in the background every time the display changes.
                    // In this way, there will be no main thread delays when a dialog is shown.
                    getContextOrDefault(displayId)
                }
        }
    }

    override val context: Context
        get() {
            if (!ShadeWindowGoesAround.isEnabled) {
                return defaultContext
            }
            val displayId = shadeDisplaysRepository.displayId.value
            return getContextOrDefault(displayId)
        }

    private fun getContextOrDefault(displayId: Int): Context {
        return try {
            traceSection({ "Getting dialog context for displayId=$displayId" }) {
                val displayWindowProperties =
                    displayWindowPropertyRepository.get().get(displayId, DIALOG_WINDOW_TYPE)
                if (displayWindowProperties == null) {
                    Log.e(
                        TAG,
                        "DisplayWindowPropertiesRepository returned null for display $displayId. Returning default one",
                    )
                    defaultContext
                } else {
                    displayWindowProperties.context
                }
            }
        } catch (e: Exception) {
            // This can happen if the display was disconnected in the meantime.
            Log.e(
                TAG,
                "Couldn't get dialog context for displayId=$displayId. Returning default one",
                e,
            )
            defaultContext
        }
    }

    private companion object {
        const val TAG = "ShadeDialogContextRepo"
        const val DIALOG_WINDOW_TYPE = TYPE_STATUS_BAR_SUB_PANEL
    }
}
