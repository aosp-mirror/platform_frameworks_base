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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardJankViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    val goneToAodTransition =
        keyguardTransitionInteractor.transition(
            edge = Edge.create(Scenes.Gone, AOD),
            edgeWithoutSceneContainer = Edge.create(GONE, AOD),
        )

    val lockscreenToAodTransition =
        keyguardTransitionInteractor.transition(
            edge = Edge.create(Scenes.Lockscreen, AOD),
            edgeWithoutSceneContainer = Edge.create(LOCKSCREEN, AOD),
        )

    val aodToLockscreenTransition =
        keyguardTransitionInteractor.transition(
            edge = Edge.create(AOD, Scenes.Lockscreen),
            edgeWithoutSceneContainer = Edge.create(AOD, LOCKSCREEN),
        )
}
