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

package com.android.systemui.communal

import android.annotation.SuppressLint
import android.app.DreamManager
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.glanceableHubAllowKeyguardWhenDreaming
import com.android.systemui.Flags.communalHub
import com.android.systemui.Flags.restartDreamOnUnocclude
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * A [CoreStartable] responsible for automatically starting the dream when the communal hub is
 * shown, to support the user swiping away the hub to enter the dream.
 */
@SysUISingleton
class CommunalDreamStartable
@Inject
constructor(
    private val powerInteractor: PowerInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val communalInteractor: CommunalInteractor,
    private val dreamManager: DreamManager,
    @Background private val bgScope: CoroutineScope,
) : CoreStartable {
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!communalHub()) {
            return
        }

        // Return to dream from occluded when not already dreaming.
        if (restartDreamOnUnocclude()) {
            keyguardTransitionInteractor.startedKeyguardTransitionStep
                .sample(keyguardInteractor.isDreaming, ::Pair)
                .filter {
                    it.first.from == KeyguardState.OCCLUDED &&
                        it.first.to == KeyguardState.DREAMING &&
                        !it.second
                }
                .onEach { dreamManager.startDream() }
                .launchIn(bgScope)
        }

        // Restart the dream underneath the hub in order to support the ability to swipe
        // away the hub to enter the dream.
        keyguardTransitionInteractor.finishedKeyguardState
            .sample(powerInteractor.isAwake, keyguardInteractor.isDreaming)
            .onEach { (finishedState, isAwake, dreaming) ->
                if (
                    finishedState == KeyguardState.GLANCEABLE_HUB &&
                        !dreaming &&
                        !glanceableHubAllowKeyguardWhenDreaming() &&
                        dreamManager.canStartDreaming(isAwake)
                ) {
                    dreamManager.startDream()
                }
            }
            .launchIn(bgScope)
    }
}
