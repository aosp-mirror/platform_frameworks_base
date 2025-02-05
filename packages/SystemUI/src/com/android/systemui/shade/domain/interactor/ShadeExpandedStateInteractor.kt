/*
 * Copyright (C) 2025 The Android Open Source Project
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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeTraceLogger.traceWaitForExpansion
import com.android.systemui.shade.domain.interactor.ShadeExpandedStateInteractor.ShadeElement
import com.android.systemui.util.kotlin.Utils.Companion.combineState
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wrapper around [ShadeInteractor] to facilitate expansion and collapse of Notifications and quick
 * settings.
 *
 * Specifically created to simplify [ShadeDisplaysInteractor] logic.
 *
 * NOTE: with [SceneContainerFlag] disabled, [currentlyExpandedElement] will always return `null`!
 */
interface ShadeExpandedStateInteractor {
    /** Returns the expanded [ShadeElement]. If none is, returns null. */
    val currentlyExpandedElement: StateFlow<ShadeElement?>

    /** An element from the shade window that can be expanded or collapsed. */
    sealed class ShadeElement {
        /** Expands the shade element, returning when the expansion is done */
        abstract suspend fun expand(reason: String)

        /** Collapses the shade element, returning when the collapse is done. */
        abstract suspend fun collapse(reason: String)
    }
}

private val EXPAND_COLLAPSE_TIMEOUT: Duration = 1.seconds

@SysUISingleton
class ShadeExpandedStateInteractorImpl
@Inject
constructor(
    shadeInteractor: ShadeInteractor,
    @Background private val bgScope: CoroutineScope,
    private val notificationElement: NotificationShadeElement,
    private val qsElement: QSShadeElement,
) : ShadeExpandedStateInteractor {

    override val currentlyExpandedElement: StateFlow<ShadeElement?> =
        if (SceneContainerFlag.isEnabled) {
            combineState(
                shadeInteractor.isShadeAnyExpanded,
                shadeInteractor.isQsExpanded,
                bgScope,
                SharingStarted.Eagerly,
            ) { isShadeAnyExpanded, isQsExpanded ->
                when {
                    isShadeAnyExpanded -> notificationElement
                    isQsExpanded -> qsElement
                    else -> null
                }
            }
        } else {
            MutableStateFlow(null)
        }
}

private suspend fun StateFlow<Float>.waitUntil(f: Float, coroutineContext: CoroutineContext) {
    // it's important to not do this in the main thread otherwise it will block any rendering.
    withContext(coroutineContext) {
        withTimeoutOrNull(EXPAND_COLLAPSE_TIMEOUT) {
            traceWaitForExpansion(expansion = f) { first { it == f } }
        }
            ?: Log.e(
                "ShadeExpStateInteractor",
                "Timed out after ${EXPAND_COLLAPSE_TIMEOUT.inWholeMilliseconds}ms while waiting " +
                    "for expansion to match $f. Current one: $value",
            )
    }
}

@SysUISingleton
class NotificationShadeElement
@Inject
constructor(
    private val shadeInteractor: ShadeInteractor,
    @Background private val bgContext: CoroutineContext,
) : ShadeElement() {
    override suspend fun expand(reason: String) {
        if (SceneContainerFlag.isEnabled) {
            shadeInteractor.expandNotificationsShade(reason)
            shadeInteractor.shadeExpansion.waitUntil(1f, bgContext)
        }
    }

    override suspend fun collapse(reason: String) {
        if (SceneContainerFlag.isEnabled) {
            shadeInteractor.collapseNotificationsShade(reason)
            shadeInteractor.shadeExpansion.waitUntil(0f, bgContext)
        }
    }
}

@SysUISingleton
class QSShadeElement
@Inject
constructor(
    private val shadeInteractor: ShadeInteractor,
    @Background private val bgContext: CoroutineContext,
) : ShadeElement() {
    override suspend fun expand(reason: String) {
        if (SceneContainerFlag.isEnabled) {
            shadeInteractor.expandQuickSettingsShade(reason)
            shadeInteractor.qsExpansion.waitUntil(1f, bgContext)
        }
    }

    override suspend fun collapse(reason: String) {
        if (SceneContainerFlag.isEnabled) {
            shadeInteractor.collapseQuickSettingsShade(reason)
            shadeInteractor.qsExpansion.waitUntil(0f, bgContext)
        }
    }
}
