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

package com.android.systemui.shade.domain.interactor

import com.android.keyguard.LockIconViewController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.ShadeRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShadeLockscreenInteractorImpl
@Inject
constructor(
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundScope: CoroutineScope,
    private val shadeInteractor: ShadeInteractor,
    private val sceneInteractor: SceneInteractor,
    private val lockIconViewController: LockIconViewController,
    shadeRepository: ShadeRepository,
) : ShadeLockscreenInteractor {

    override val udfpsTransitionToFullShadeProgress =
        shadeRepository.udfpsTransitionToFullShadeProgress

    override fun expandToNotifications() {
        changeToShadeScene()
    }

    override val isExpanded
        get() = shadeInteractor.isAnyExpanded.value

    override fun startBouncerPreHideAnimation() {
        // TODO("b/324280998") Implement replacement or delete
    }

    override fun dozeTimeTick() {
        lockIconViewController.dozeTimeTick()
    }

    override fun blockExpansionForCurrentTouch() {
        // TODO("b/324280998") Implement replacement or delete
    }

    override fun resetViews(animate: Boolean) {
        // The existing comment to the only call to this claims it only calls it to collapse QS
        changeToShadeScene()
    }

    override fun setPulsing(pulsing: Boolean) {
        // Now handled elsewhere. Do nothing.
    }

    override fun transitionToExpandedShade(delay: Long) {
        backgroundScope.launch {
            delay(delay)
            withContext(mainDispatcher) { changeToShadeScene() }
        }
    }

    override fun resetViewGroupFade() {
        // Now handled elsewhere. Do nothing.
    }

    override fun setKeyguardTransitionProgress(keyguardAlpha: Float, keyguardTranslationY: Int) {
        // Now handled elsewhere. Do nothing.
    }

    override fun setOverStretchAmount(amount: Float) {
        // Now handled elsewhere. Do nothing.
    }

    override fun setKeyguardStatusBarAlpha(alpha: Float) {
        // TODO(b/325072511) delete this
    }

    override fun showAodUi() {
        sceneInteractor.changeScene(Scenes.Lockscreen, "showAodUi", sceneState = KeyguardState.AOD)
        // TODO(b/330311871) implement transition to AOD
    }

    private fun changeToShadeScene() {
        sceneInteractor.changeScene(
            SceneFamilies.NotifShade,
            "ShadeLockscreenInteractorImpl.expandToNotifications",
        )
    }
}
