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

package com.android.systemui.display.domain.interactor

import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.flags.Flags
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Provides information about an external connected display. */
interface ConnectedDisplayInteractor {
    /**
     * Provides the current external display state.
     *
     * The state is:
     * - [State.CONNECTED] when there is at least one display with [TYPE_EXTERNAL].
     * - [State.CONNECTED_SECURE] when is at least one display with both [TYPE_EXTERNAL] AND
     *   [Display.FLAG_SECURE] set
     */
    val connectedDisplayState: Flow<State>

    /**
     * Indicates that there is a new connected display (either an external display or a virtual
     * device owned mirror display).
     */
    val connectedDisplayAddition: Flow<Unit>

    /** Pending display that can be enabled to be used by the system. */
    val pendingDisplay: Flow<PendingDisplay?>

    /** Possible connected display state. */
    enum class State {
        DISCONNECTED,
        CONNECTED,
        CONNECTED_SECURE,
    }

    /** Represents a connected display that has not been enabled yet for the UI layer. */
    interface PendingDisplay {
        /** Enables the display, making it available to the system. */
        suspend fun enable()

        /**
         * Ignores the pending display.
         *
         * When called, this specific display id doesn't appear as pending anymore until the display
         * is disconnected and reconnected again.
         */
        suspend fun ignore()
    }
}

@SysUISingleton
class ConnectedDisplayInteractorImpl
@Inject
constructor(
    private val virtualDeviceManager: VirtualDeviceManager,
    keyguardRepository: KeyguardRepository,
    displayRepository: DisplayRepository,
    @Background backgroundCoroutineDispatcher: CoroutineDispatcher,
) : ConnectedDisplayInteractor {

    override val connectedDisplayState: Flow<State> =
        displayRepository.displays
            .map { displays ->
                val externalDisplays = displays.filter { isExternalDisplay(it) }

                val secureExternalDisplays = externalDisplays.filter { isSecureDisplay(it) }

                val virtualDeviceMirrorDisplays =
                    displays.filter { isVirtualDeviceOwnedMirrorDisplay(it) }

                if (externalDisplays.isEmpty() && virtualDeviceMirrorDisplays.isEmpty()) {
                    State.DISCONNECTED
                } else if (!secureExternalDisplays.isEmpty()) {
                    State.CONNECTED_SECURE
                } else {
                    State.CONNECTED
                }
            }
            .flowOn(backgroundCoroutineDispatcher)
            .distinctUntilChanged()

    override val connectedDisplayAddition: Flow<Unit> =
        displayRepository.displayAdditionEvent
            .filter {
                it != null && (isExternalDisplay(it) || isVirtualDeviceOwnedMirrorDisplay(it))
            }
            .flowOn(backgroundCoroutineDispatcher)
            .map {} // map to Unit

    // Provides the pending display only if the lockscreen is unlocked
    override val pendingDisplay: Flow<PendingDisplay?> =
        displayRepository.pendingDisplay.combine(keyguardRepository.isKeyguardShowing) {
            repositoryPendingDisplay,
            keyguardShowing ->
            if (repositoryPendingDisplay != null && !keyguardShowing) {
                repositoryPendingDisplay.toInteractorPendingDisplay()
            } else {
                null
            }
        }

    private fun DisplayRepository.PendingDisplay.toInteractorPendingDisplay(): PendingDisplay =
        object : PendingDisplay {
            override suspend fun enable() = this@toInteractorPendingDisplay.enable()
            override suspend fun ignore() = this@toInteractorPendingDisplay.ignore()
        }

    private fun isExternalDisplay(display: Display): Boolean {
        return display.type == Display.TYPE_EXTERNAL
    }

    private fun isSecureDisplay(display: Display): Boolean {
        return display.flags and Display.FLAG_SECURE != 0
    }

    private fun isVirtualDeviceOwnedMirrorDisplay(display: Display): Boolean {
        return Flags.interactiveScreenMirror() &&
            virtualDeviceManager.isVirtualDeviceOwnedMirrorDisplay(display.displayId)
    }
}
