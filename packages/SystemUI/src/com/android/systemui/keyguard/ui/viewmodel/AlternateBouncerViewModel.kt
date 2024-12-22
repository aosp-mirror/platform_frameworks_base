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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.graphics.Color
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@ExperimentalCoroutinesApi
class AlternateBouncerViewModel
@Inject
constructor(
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    alternateBouncerInteractor: Lazy<AlternateBouncerInteractor>,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
) {
    // When we're fully transitioned to the AlternateBouncer, the alpha of the scrim should be:
    private val alternateBouncerScrimAlpha = .66f

    /** Reports the alternate bouncer visible state if the scene container flag is enabled. */
    val isVisible: Flow<Boolean> =
        alternateBouncerInteractor.get().isVisible.onEach { SceneContainerFlag.assertInNewMode() }

    /** Progress to a fully transitioned alternate bouncer. 1f represents fully transitioned. */
    val transitionToAlternateBouncerProgress: Flow<Float> =
        keyguardTransitionInteractor.transitionValue(ALTERNATE_BOUNCER)

    /** An observable for the scrim alpha. */
    val scrimAlpha = transitionToAlternateBouncerProgress.map { it * alternateBouncerScrimAlpha }

    /** An observable for the scrim color. Change color for easier debugging. */
    val scrimColor: Flow<Int> = flowOf(Color.BLACK)

    val registerForDismissGestures: Flow<Boolean> =
        transitionToAlternateBouncerProgress.map { it == 1f }.distinctUntilChanged()

    fun onTapped() {
        statusBarKeyguardViewManager.showPrimaryBouncer(/* scrimmed */ true)
    }

    fun onRemovedFromWindow() {
        statusBarKeyguardViewManager.hideAlternateBouncer(false)
    }

    fun onBackRequested() {
        statusBarKeyguardViewManager.hideAlternateBouncer(false)
        dismissCallbackRegistry.notifyDismissCancelled()
        primaryBouncerInteractor.setDismissAction(null, null)
    }
}
