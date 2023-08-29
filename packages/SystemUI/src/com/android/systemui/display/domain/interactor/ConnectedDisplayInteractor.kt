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

import android.hardware.display.DisplayManager
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.util.traceSection
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Pending display that can be enabled to be used by the system. */
    val pendingDisplay: Flow<PendingDisplay?>

    /** Possible connected display state. */
    enum class State {
        DISCONNECTED,
        CONNECTED,
        CONNECTED_SECURE,
    }

    /** Represents a connected display that has not been enabled yet. */
    interface PendingDisplay {
        /** Enables the display, making it available to the system. */
        fun enable()

        /** Disables the display, making it unavailable to the system. */
        fun disable()
    }
}

@SysUISingleton
class ConnectedDisplayInteractorImpl
@Inject
constructor(
    private val displayManager: DisplayManager,
    keyguardRepository: KeyguardRepository,
    displayRepository: DisplayRepository,
) : ConnectedDisplayInteractor {

    override val connectedDisplayState: Flow<State> =
        displayRepository.displays
            .map { displays ->
                val externalDisplays =
                    displays.filter { display -> display.type == Display.TYPE_EXTERNAL }

                val secureExternalDisplays =
                    externalDisplays.filter { it.flags and Display.FLAG_SECURE != 0 }

                if (externalDisplays.isEmpty()) {
                    State.DISCONNECTED
                } else if (!secureExternalDisplays.isEmpty()) {
                    State.CONNECTED_SECURE
                } else {
                    State.CONNECTED
                }
            }
            .distinctUntilChanged()

    // Provides the pending display only if the lockscreen is unlocked
    override val pendingDisplay: Flow<PendingDisplay?> =
        displayRepository.pendingDisplayId.combine(keyguardRepository.isKeyguardUnlocked) {
            pendingDisplayId,
            keyguardUnlocked ->
            if (pendingDisplayId != null && keyguardUnlocked) {
                pendingDisplayId.toPendingDisplay()
            } else {
                null
            }
        }

    private fun Int.toPendingDisplay() =
        object : PendingDisplay {
            val id = this@toPendingDisplay
            override fun enable() {
                traceSection("DisplayRepository#enable($id)") {
                    displayManager.enableConnectedDisplay(id)
                }
            }
            override fun disable() {
                traceSection("DisplayRepository#enable($id)") {
                    displayManager.disableConnectedDisplay(id)
                }
            }
        }
}
