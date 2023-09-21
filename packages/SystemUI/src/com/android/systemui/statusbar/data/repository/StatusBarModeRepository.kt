/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.data.repository

import android.view.WindowInsets
import android.view.WindowInsetsController
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A repository for the current mode of the status bar on the homescreen (translucent, transparent,
 * opaque, lights out, hidden, etc.).
 *
 * Note: These status bar modes are status bar *window* states that are sent to us from
 * WindowManager, not determined internally.
 */
interface StatusBarModeRepository {
    /**
     * True if the status bar window is showing transiently and will disappear soon, and false
     * otherwise. ("Otherwise" in this case means the status bar is persistently hidden OR
     * persistently shown.)
     *
     * This behavior is controlled by WindowManager via
     * [android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE], *not* calculated
     * internally. SysUI merely obeys the behavior sent to us.
     */
    val isTransientShown: StateFlow<Boolean>

    /**
     * True the focused window is fullscreen (aka immersive) and false otherwise.
     *
     * Typically, the only time the status bar window is hidden is when the focused window is
     * fullscreen.
     */
    val isInFullscreenMode: StateFlow<Boolean>

    /**
     * Requests for the status bar to be shown transiently.
     *
     * TODO(b/277764509): Don't allow [CentralSurfaces] to set the transient mode; have it
     *   determined internally instead.
     */
    fun showTransient()

    /**
     * Requests for the status bar to be no longer showing transiently.
     *
     * TODO(b/277764509): Don't allow [CentralSurfaces] to set the transient mode; have it
     *   determined internally instead.
     */
    fun clearTransient()
}

@SysUISingleton
class StatusBarModeRepositoryImpl
@Inject
constructor(
    @Application scope: CoroutineScope,
    @DisplayId thisDisplayId: Int,
    private val commandQueue: CommandQueue,
) : StatusBarModeRepository, CoreStartable {

    private val commandQueueCallback =
        object : CommandQueue.Callbacks {
            override fun showTransient(
                displayId: Int,
                @WindowInsets.Type.InsetsType types: Int,
                isGestureOnSystemBar: Boolean,
            ) {
                if (isTransientRelevant(displayId, types)) {
                    _isTransientShown.value = true
                }
            }

            override fun abortTransient(displayId: Int, @WindowInsets.Type.InsetsType types: Int) {
                if (isTransientRelevant(displayId, types)) {
                    _isTransientShown.value = false
                }
            }

            private fun isTransientRelevant(
                displayId: Int,
                @WindowInsets.Type.InsetsType types: Int,
            ): Boolean {
                return displayId == thisDisplayId && (types and WindowInsets.Type.statusBars() != 0)
            }

            override fun onSystemBarAttributesChanged(
                displayId: Int,
                @WindowInsetsController.Appearance appearance: Int,
                appearanceRegions: Array<AppearanceRegion>,
                navbarColorManagedByIme: Boolean,
                @WindowInsetsController.Behavior behavior: Int,
                @WindowInsets.Type.InsetsType requestedVisibleTypes: Int,
                packageName: String,
                letterboxDetails: Array<LetterboxDetails>,
            ) {
                if (displayId != thisDisplayId) return
                _originalStatusBarAttributes.value =
                    StatusBarAttributes(
                        requestedVisibleTypes,
                    )
            }
        }

    override fun start() {
        commandQueue.addCallback(commandQueueCallback)
    }

    private val _isTransientShown = MutableStateFlow(false)
    override val isTransientShown: StateFlow<Boolean> = _isTransientShown.asStateFlow()

    private val _originalStatusBarAttributes = MutableStateFlow<StatusBarAttributes?>(null)

    override val isInFullscreenMode: StateFlow<Boolean> =
        _originalStatusBarAttributes
            .map { params ->
                val requestedVisibleTypes = params?.requestedVisibleTypes ?: return@map false
                // When the status bar is not requested visible, we assume we're in fullscreen mode.
                requestedVisibleTypes and WindowInsets.Type.statusBars() == 0
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override fun showTransient() {
        _isTransientShown.value = true
    }

    override fun clearTransient() {
        _isTransientShown.value = false
    }

    /**
     * Internal class keeping track of the raw status bar attributes received from the callback.
     * Should never be exposed.
     */
    private data class StatusBarAttributes(
        @WindowInsets.Type.InsetsType val requestedVisibleTypes: Int,
    )
}
