/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.common.shared.model.Position
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Encapsulates business-logic specifically related to the keyguard bottom area. */
@SysUISingleton
class KeyguardBottomAreaInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
) {
    /** Whether to animate the next doze mode transition. */
    val animateDozingTransitions: Flow<Boolean> = repository.animateBottomAreaDozingTransitions
    /** The amount of alpha for the UI components of the bottom area. */
    val alpha: Flow<Float> = repository.bottomAreaAlpha
    /** The position of the keyguard clock. */
    val clockPosition: Flow<Position> = repository.clockPosition

    fun setClockPosition(x: Int, y: Int) {
        repository.setClockPosition(x, y)
    }

    fun setAlpha(alpha: Float) {
        repository.setBottomAreaAlpha(alpha)
    }

    fun setAnimateDozingTransitions(animate: Boolean) {
        repository.setAnimateDozingTransitions(animate)
    }

    /**
     * Returns whether the keyguard bottom area should be constrained to the top of the lock icon
     */
    fun shouldConstrainToTopOfLockIcon(): Boolean = repository.isUdfpsSupported()
}
