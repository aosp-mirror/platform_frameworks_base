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

package com.android.systemui.statusbar.events

import androidx.core.animation.Animator
import androidx.core.animation.AnimatorSet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.SystemEventChipAnimationControllerStore
import javax.inject.Inject

/**
 * A [SystemEventChipAnimationController] that handles animations for multiple displays. It
 * delegates the animation tasks to individual controllers for each display.
 */
@SysUISingleton
class MultiDisplaySystemEventChipAnimationController
@Inject
constructor(
    private val displayRepository: DisplayRepository,
    private val controllerStore: SystemEventChipAnimationControllerStore,
) : SystemEventChipAnimationController {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun prepareChipAnimation(viewCreator: ViewCreator) {
        forEachController { it.prepareChipAnimation(viewCreator) }
    }

    override fun init() {
        forEachController { it.init() }
    }

    override fun stop() {
        forEachController { it.stop() }
    }

    override fun announceForAccessibility(contentDescriptions: String) {
        forEachController { it.announceForAccessibility(contentDescriptions) }
    }

    override fun onSystemEventAnimationBegin(): Animator {
        val animators = controllersForAllDisplays().map { it.onSystemEventAnimationBegin() }
        return AnimatorSet().apply { playTogether(animators) }
    }

    override fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator {
        val animators =
            controllersForAllDisplays().map { it.onSystemEventAnimationFinish(hasPersistentDot) }
        return AnimatorSet().apply { playTogether(animators) }
    }

    private fun forEachController(consumer: (SystemEventChipAnimationController) -> Unit) {
        controllersForAllDisplays().forEach { consumer(it) }
    }

    private fun controllersForAllDisplays() =
        displayRepository.displays.value.map { controllerStore.forDisplay(it.displayId) }
}
