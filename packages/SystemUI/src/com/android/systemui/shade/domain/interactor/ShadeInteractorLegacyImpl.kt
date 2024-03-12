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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** ShadeInteractor implementation for the legacy codebase, e.g. NPVC. */
@SysUISingleton
class ShadeInteractorLegacyImpl
@Inject
constructor(
    @Application val scope: CoroutineScope,
    keyguardRepository: KeyguardRepository,
    sharedNotificationContainerInteractor: SharedNotificationContainerInteractor,
    repository: ShadeRepository,
) : BaseShadeInteractor {
    /**
     * The amount [0-1] that the shade has been opened. Uses stateIn to avoid redundant calculations
     * in downstream flows.
     */
    override val shadeExpansion: Flow<Float> =
        combine(
                repository.lockscreenShadeExpansion,
                keyguardRepository.statusBarState,
                repository.legacyShadeExpansion,
                repository.qsExpansion,
                sharedNotificationContainerInteractor.isSplitShadeEnabled
            ) {
                lockscreenShadeExpansion,
                statusBarState,
                legacyShadeExpansion,
                qsExpansion,
                splitShadeEnabled ->
                when (statusBarState) {
                    // legacyShadeExpansion is 1 instead of 0 when QS is expanded
                    StatusBarState.SHADE ->
                        if (!splitShadeEnabled && qsExpansion > 0f) 0f else legacyShadeExpansion
                    StatusBarState.KEYGUARD -> lockscreenShadeExpansion
                    // dragDownAmount, which drives lockscreenShadeExpansion resets to 0f when
                    // the pointer is lifted and the lockscreen shade is fully expanded
                    StatusBarState.SHADE_LOCKED -> 1f
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    override val qsExpansion: StateFlow<Float> = repository.qsExpansion

    override val isQsExpanded: StateFlow<Boolean> = repository.legacyIsQsExpanded

    override val isQsBypassingShade: Flow<Boolean> = repository.legacyExpandImmediate
    override val isQsFullscreen: Flow<Boolean> = repository.legacyQsFullscreen

    override val anyExpansion: StateFlow<Float> =
        createAnyExpansionFlow(scope, shadeExpansion, qsExpansion)

    override val isAnyExpanded =
        repository.legacyExpandedOrAwaitingInputTransfer.stateIn(
            scope,
            SharingStarted.Eagerly,
            false
        )

    override val isUserInteractingWithShade: Flow<Boolean> =
        combine(
            userInteractingFlow(repository.legacyShadeTracking, repository.legacyShadeExpansion),
            repository.legacyLockscreenShadeTracking
        ) { legacyShadeTracking, legacyLockscreenShadeTracking ->
            legacyShadeTracking || legacyLockscreenShadeTracking
        }

    override val isUserInteractingWithQs: Flow<Boolean> =
        userInteractingFlow(repository.legacyQsTracking, repository.qsExpansion)

    override val shadeMode: StateFlow<ShadeMode> = repository.shadeMode

    /**
     * Return a flow for whether a user is interacting with an expandable shade component using
     * tracking and expansion flows. NOTE: expansion must be a `StateFlow` to guarantee that
     * [expansion.first] checks the current value of the flow.
     */
    private fun userInteractingFlow(
        tracking: Flow<Boolean>,
        expansion: StateFlow<Float>
    ): Flow<Boolean> {
        return flow {
            // initial value is false
            emit(false)
            while (currentCoroutineContext().isActive) {
                // wait for tracking to become true
                tracking.first { it }
                emit(true)
                // wait for tracking to become false
                tracking.first { !it }
                // wait for expansion to complete in either direction
                expansion.first { it <= 0f || it >= 1f }
                // interaction complete
                emit(false)
            }
        }
    }
}
