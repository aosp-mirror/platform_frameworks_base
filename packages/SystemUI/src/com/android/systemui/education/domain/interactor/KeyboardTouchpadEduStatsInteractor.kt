/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.contextualeducation.GestureType
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Encapsulates the update functions of KeyboardTouchpadEduStatsInteractor. This encapsulation is
 * for having a different implementation of interactor when the feature flag is off.
 */
interface KeyboardTouchpadEduStatsInteractor {
    fun incrementSignalCount(gestureType: GestureType)

    fun updateShortcutTriggerTime(gestureType: GestureType)
}

/** Allow update to education data related to keyboard/touchpad. */
@SysUISingleton
class KeyboardTouchpadEduStatsInteractorImpl
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val contextualEducationInteractor: ContextualEducationInteractor
) : KeyboardTouchpadEduStatsInteractor {

    override fun incrementSignalCount(gestureType: GestureType) {
        // Todo: check if keyboard/touchpad is connected before update
        backgroundScope.launch { contextualEducationInteractor.incrementSignalCount(gestureType) }
    }

    override fun updateShortcutTriggerTime(gestureType: GestureType) {
        backgroundScope.launch {
            contextualEducationInteractor.updateShortcutTriggerTime(gestureType)
        }
    }
}
