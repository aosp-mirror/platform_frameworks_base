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
import android.content.res.Configuration
import android.util.Log
import android.window.WindowContext
import androidx.annotation.UiThread
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.CoreStartable
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.ShadeDisplayChangeLatencyTracker
import com.android.systemui.shade.ShadeTraceLogger.logMoveShadeWindowTo
import com.android.systemui.shade.ShadeTraceLogger.t
import com.android.systemui.shade.ShadeTraceLogger.traceReparenting
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.display.ShadeExpansionIntent
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.row.NotificationRebindingTracker
import com.android.systemui.statusbar.notification.stack.NotificationStackRebindingHider
import com.android.systemui.statusbar.phone.ConfigurationForwarder
import com.android.systemui.util.kotlin.getOrNull
import com.android.window.flags.Flags
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Handles Shade window display change when [ShadeDisplaysRepository.displayId] changes. */
@SysUISingleton
class ShadeDisplaysInteractor
@Inject
constructor(
    private val shadePositionRepository: ShadeDisplaysRepository,
    @ShadeDisplayAware private val shadeContext: WindowContext,
    @ShadeDisplayAware private val configurationRepository: ConfigurationRepository,
    @Background private val bgScope: CoroutineScope,
    @Main private val mainThreadContext: CoroutineContext,
    private val shadeDisplayChangeLatencyTracker: ShadeDisplayChangeLatencyTracker,
    shadeExpandedInteractor: Optional<ShadeExpandedStateInteractor>,
    private val shadeExpansionIntent: ShadeExpansionIntent,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val notificationRebindingTracker: NotificationRebindingTracker,
    notificationStackRebindingHider: Optional<NotificationStackRebindingHider>,
    @ShadeDisplayAware private val configForwarder: ConfigurationForwarder,
) : CoreStartable {

    private val shadeExpandedInteractor = requireOptional(shadeExpandedInteractor)
    private val notificationStackRebindingHider = requireOptional(notificationStackRebindingHider)

    private val hasActiveNotifications: Boolean
        get() = activeNotificationsInteractor.areAnyNotificationsPresentValue

    override fun start() {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        listenForWindowContextConfigChanges()
        bgScope.launchTraced(TAG) {
            shadePositionRepository.displayId.collectLatest { displayId ->
                moveShadeWindowTo(displayId)
            }
        }
    }

    private fun listenForWindowContextConfigChanges() {
        shadeContext.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    configForwarder.onConfigurationChanged(newConfig)
                }

                override fun onLowMemory() {}
            }
        )
    }

    /** Tries to move the shade. If anything wrong happens, fails gracefully without crashing. */
    private suspend fun moveShadeWindowTo(destinationId: Int) {
        Log.d(TAG, "Trying to move shade window to display with id $destinationId")
        logMoveShadeWindowTo(destinationId)
        var currentId = -1
        try {
            // Why using the shade context here instead of the view's Display?
            // The context's display is updated before the view one, so it is a better indicator of
            // which display the shade is supposed to be at. The View display is updated after the
            // first
            // rendering with the new config.
            val currentDisplay = shadeContext.display ?: error("Current shade display is null")
            currentId = currentDisplay.displayId
            if (currentId == destinationId) {
                error("Trying to move the shade to a display it was already in")
            }

            withContext(mainThreadContext) {
                traceReparenting {
                    collapseAndExpandShadeIfNeeded(destinationId) {
                        shadeDisplayChangeLatencyTracker.onShadeDisplayChanging(destinationId)
                        reparentToDisplayId(id = destinationId)
                    }
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

    private suspend fun collapseAndExpandShadeIfNeeded(newDisplayId: Int, reparent: () -> Unit) {
        val previouslyExpandedElement = shadeExpandedInteractor.currentlyExpandedElement.value
        previouslyExpandedElement?.collapse(reason = COLLAPSE_EXPAND_REASON)
        val notificationStackHidden =
            if (!hasActiveNotifications) {
                // This covers the case the previous move was cancelled before setting the
                // visibility back. As there are no notifications, nothing can flicker here, and
                // showing them all of a sudden is ok.
                notificationStackRebindingHider.setVisible(visible = true, animated = false)
                false
            } else {
                // Hiding as otherwise there might be flickers as the inflation with new dimensions
                // happens async and views with the old dimensions are not removed until the
                // inflation succeeds.
                notificationStackRebindingHider.setVisible(visible = false, animated = false)
                true
            }

        reparent()

        val elementToExpand =
            shadeExpansionIntent.consumeExpansionIntent() ?: previouslyExpandedElement
        // If the user was trying to expand a specific shade element, let's make sure to expand
        // that one. Otherwise, we can just re-expand the previous expanded element.
        elementToExpand?.expand(COLLAPSE_EXPAND_REASON)
        if (notificationStackHidden) {
            if (hasActiveNotifications) {
                // "onMovedToDisplay" is what synchronously triggers the rebinding of views: we need
                // to wait for it to be received.
                waitForOnMovedToDisplayDispatchedToView(newDisplayId)
                waitForNotificationsRebinding()
            }
            notificationStackRebindingHider.setVisible(visible = true, animated = true)
        }
    }

    private suspend fun waitForOnMovedToDisplayDispatchedToView(newDisplayId: Int) {
        withContext(bgScope.coroutineContext) {
            t.traceAsync({
                "waitForOnMovedToDisplayDispatchedToView(newDisplayId=$newDisplayId)"
            }) {
                withTimeoutOrNull(TIMEOUT) {
                    configurationRepository.onMovedToDisplay.filter { it == newDisplayId }.first()
                    t.instant { "onMovedToDisplay received with $newDisplayId" }
                }
                    ?: errorLog(
                        "Timed out while waiting for onMovedToDisplay to be dispatched to " +
                            "the shade root view in ShadeDisplaysInteractor"
                    )
            }
        }
    }

    private suspend fun waitForNotificationsRebinding() {
        // here we don't need to wait for rebinding to appear (e.g. going > 0), as it already
        // happened synchronously when the new configuration was received by ViewConfigCoordinator.
        t.traceAsync("waiting for notifications rebinding to finish") {
            withTimeoutOrNull(TIMEOUT) {
                notificationRebindingTracker.rebindingInProgressCount.first { it == 0 }
            } ?: errorLog("Timed out while waiting for inflations to finish")
        }
    }

    private fun errorLog(s: String) {
        Log.e(TAG, s)
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
        t.traceSyncAndAsync({ "reparentToDisplayId(id=$id)" }) {
            shadeContext.reparentToDisplay(id)
        }
    }

    private companion object {
        const val TAG = "ShadeDisplaysInteractor"
        const val COLLAPSE_EXPAND_REASON = "Shade window move"
        val TIMEOUT = 1.seconds

        /**
         * [ShadeDisplaysInteractor] is bound in the SystemUI module for all variants, but needs
         * some specific dependencies to be bound from each variant (e.g.
         * [ShadeExpandedStateInteractor] or [NotificationStackRebindingHider]). When those are not
         * bound, this class is not expected to be instantiated, and trying to instantiate it would
         * crash.
         */
        inline fun <reified T> requireOptional(optional: Optional<T>): T {
            return optional.getOrNull()
                ?: error(
                    """
                ${T::class.java.simpleName} must be provided for ShadeDisplaysInteractor to work.
                If it is not, it means this is being instantiated in a SystemUI variant that
                shouldn't.
                """
                        .trimIndent()
                )
        }
    }
}
