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

import android.util.Log
import android.window.WindowContext
import androidx.annotation.UiThread
import com.android.app.tracing.coroutines.launchTraced
import com.android.app.tracing.traceSection
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.ShadeDisplayChangeLatencyTracker
import com.android.systemui.shade.ShadeTraceLogger.logMoveShadeWindowTo
import com.android.systemui.shade.ShadeTraceLogger.traceReparenting
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.display.ShadeExpansionIntent
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.window.flags.Flags
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
    private val shadePositionRepository: ShadeDisplaysRepository,
    @ShadeDisplayAware private val shadeContext: WindowContext,
    @Background private val bgScope: CoroutineScope,
    @Main private val mainThreadContext: CoroutineContext,
    private val shadeDisplayChangeLatencyTracker: ShadeDisplayChangeLatencyTracker,
    shadeExpandedInteractor: Optional<ShadeExpandedStateInteractor>,
    private val shadeExpansionIntent: ShadeExpansionIntent,
) : CoreStartable {

    private val shadeExpandedInteractor =
        shadeExpandedInteractor.orElse(null)
            ?: error(
                """
            ShadeExpandedStateInteractor must be provided for ShadeDisplaysInteractor to work.
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
        logMoveShadeWindowTo(destinationId)
        // Why using the shade context here instead of the view's Display?
        // The context's display is updated before the view one, so it is a better indicator of
        // which display the shade is supposed to be at. The View display is updated after the first
        // rendering with the new config.
        val currentDisplay = shadeContext.display
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
            withContext(mainThreadContext) {
                traceReparenting {
                    shadeDisplayChangeLatencyTracker.onShadeDisplayChanging(destinationId)
                    collapseAndExpandShadeIfNeeded { reparentToDisplayId(id = destinationId) }
                    checkContextDisplayMatchesExpected(destinationId)
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(
                TAG,
                "Unable to move the shade window from display $currentId to $destinationId",
                e,
            )
        }
    }

    private suspend fun collapseAndExpandShadeIfNeeded(wrapped: () -> Unit) {
        val previouslyExpandedElement = shadeExpandedInteractor.currentlyExpandedElement.value
        previouslyExpandedElement?.collapse(reason = COLLAPSE_EXPAND_REASON)

        wrapped()

        // If the user was trying to expand a specific shade element, let's make sure to expand
        // that one. Otherwise, we can just re-expand the previous expanded element.
        shadeExpansionIntent.consumeExpansionIntent()?.expand(COLLAPSE_EXPAND_REASON)
            ?: previouslyExpandedElement?.expand(reason = COLLAPSE_EXPAND_REASON)
    }

    private fun checkContextDisplayMatchesExpected(destinationId: Int) {
        if (shadeContext.displayId != destinationId) {
            Log.wtf(
                TAG,
                "Shade context display id doesn't match the expected one after the move. " +
                    "actual=${shadeContext.displayId} expected=$destinationId. " +
                    "This means something wrong happened while trying to move the shade. " +
                    "Flag reparentWindowTokenApi=${Flags.reparentWindowTokenApi()}",
            )
        }
    }

    @UiThread
    private fun reparentToDisplayId(id: Int) {
        traceSection({ "reparentToDisplayId(id=$id)" }) { shadeContext.reparentToDisplay(id) }
    }

    private companion object {
        const val TAG = "ShadeDisplaysInteractor"
        const val COLLAPSE_EXPAND_REASON = "Shade window move"
    }
}
