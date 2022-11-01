/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE_LOCKED
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class LockscreenBouncerTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardRepository: KeyguardRepository,
    private val shadeRepository: ShadeRepository,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor
) : TransitionInteractor("LOCKSCREEN<->BOUNCER") {

    private var transitionId: UUID? = null

    override fun start() {
        scope.launch {
            shadeRepository.shadeModel
                .sample(
                    combine(
                        keyguardTransitionInteractor.finishedKeyguardState,
                        keyguardRepository.statusBarState,
                    ) { keyguardState, statusBarState ->
                        Pair(keyguardState, statusBarState)
                    },
                    { shadeModel, pair -> Triple(shadeModel, pair.first, pair.second) }
                )
                .collect { triple ->
                    val (shadeModel, keyguardState, statusBarState) = triple

                    val id = transitionId
                    if (id != null) {
                        // An existing `id` means a transition is started, and calls to
                        // `updateTransition` will control it until FINISHED
                        keyguardTransitionRepository.updateTransition(
                            id,
                            shadeModel.expansionAmount,
                            if (
                                shadeModel.expansionAmount == 0f || shadeModel.expansionAmount == 1f
                            ) {
                                transitionId = null
                                TransitionState.FINISHED
                            } else {
                                TransitionState.RUNNING
                            }
                        )
                    } else {
                        // TODO (b/251849525): Remove statusbarstate check when that state is
                        // integrated
                        // into KeyguardTransitionRepository
                        if (
                            keyguardState == KeyguardState.LOCKSCREEN &&
                                shadeModel.isUserDragging &&
                                statusBarState != SHADE_LOCKED
                        ) {
                            transitionId =
                                keyguardTransitionRepository.startTransition(
                                    TransitionInfo(
                                        ownerName = name,
                                        from = KeyguardState.LOCKSCREEN,
                                        to = KeyguardState.BOUNCER,
                                        animator = null,
                                    )
                                )
                        }
                    }
                }
        }
    }
}
