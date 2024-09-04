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

package com.android.systemui.shade

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestableContext
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert

/** Sets up shade state for tests for either value of the scene container flag. */
class ShadeTestUtil constructor(val delegate: ShadeTestUtilDelegate) {

    /** Sets shade expansion to a value between 0-1. */
    fun setShadeExpansion(shadeExpansion: Float) {
        delegate.assertFlagValid()
        delegate.setShadeExpansion(shadeExpansion)
    }

    /** Sets QS expansion to a value between 0-1. */
    fun setQsExpansion(qsExpansion: Float) {
        delegate.assertFlagValid()
        delegate.setQsExpansion(qsExpansion)
    }

    /** Sets both shade and QS expansion. One value must be zero or values must add up to 1f. */
    fun setShadeAndQsExpansion(shadeExpansion: Float, qsExpansion: Float) {
        Assert.assertTrue(
            "One expansion must be zero or both must add up to 1",
            shadeExpansion == 0f || qsExpansion == 0f || shadeExpansion + qsExpansion == 1f,
        )
        delegate.assertFlagValid()
        delegate.setShadeAndQsExpansion(shadeExpansion, qsExpansion)
    }

    /** Sets the shade expansion on the lockscreen to the given amount from 0-1. */
    fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        delegate.assertFlagValid()
        delegate.setLockscreenShadeExpansion(lockscreenShadeExpansion)
    }

    /** Sets whether the user is moving the shade with touch input on Lockscreen. */
    fun setLockscreenShadeTracking(lockscreenShadeTracking: Boolean) {
        delegate.assertFlagValid()
        delegate.setLockscreenShadeTracking(lockscreenShadeTracking)
    }

    /** Sets whether the user is moving the shade with touch input. */
    fun setTracking(tracking: Boolean) {
        delegate.assertFlagValid()
        delegate.setTracking(tracking)
    }

    /** Sets the shade to half collapsed with no touch input. */
    fun programmaticCollapseShade() {
        delegate.assertFlagValid()
        delegate.programmaticCollapseShade()
    }

    fun setQsFullscreen(qsFullscreen: Boolean) {
        delegate.assertFlagValid()
        delegate.setQsFullscreen(qsFullscreen)
    }

    fun setLegacyExpandedOrAwaitingInputTransfer(legacyExpandedOrAwaitingInputTransfer: Boolean) {
        delegate.assertFlagValid()
        delegate.setLegacyExpandedOrAwaitingInputTransfer(legacyExpandedOrAwaitingInputTransfer)
    }

    fun setSplitShade(splitShade: Boolean) {
        delegate.assertFlagValid()
        delegate.setSplitShade(splitShade)
    }
}

/** Sets up shade state for tests for a specific value of the scene container flag. */
interface ShadeTestUtilDelegate {
    /** Asserts that the scene container flag matches this implementation. */
    fun assertFlagValid()

    /** Sets both shade and QS expansion. One value must be zero or values must add up to 1f. */
    fun setShadeAndQsExpansion(shadeExpansion: Float, qsExpansion: Float)

    /** Sets the shade expansion on the lockscreen to the given amount from 0-1. */
    fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float)

    /** Sets whether the user is moving the shade with touch input. */
    fun setLockscreenShadeTracking(lockscreenShadeTracking: Boolean)

    /** Sets whether the user is moving the shade with touch input. */
    fun setTracking(tracking: Boolean)

    /** Sets shade expansion to a value between 0-1. */
    fun setShadeExpansion(shadeExpansion: Float)

    /** Sets QS expansion to a value between 0-1. */
    fun setQsExpansion(qsExpansion: Float)

    /** Sets the shade to half collapsed with no touch input. */
    fun programmaticCollapseShade()

    fun setQsFullscreen(qsFullscreen: Boolean)

    fun setLegacyExpandedOrAwaitingInputTransfer(legacyExpandedOrAwaitingInputTransfer: Boolean)

    fun setSplitShade(splitShade: Boolean)
}

/** Sets up shade state for tests when the scene container flag is disabled. */
class ShadeTestUtilLegacyImpl(
    val testScope: TestScope,
    val shadeRepository: FakeShadeRepository,
    val context: SysuiTestableContext
) : ShadeTestUtilDelegate {
    override fun setShadeAndQsExpansion(shadeExpansion: Float, qsExpansion: Float) {
        shadeRepository.setLegacyShadeExpansion(shadeExpansion)
        shadeRepository.setQsExpansion(qsExpansion)
        testScope.runCurrent()
    }

    override fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        shadeRepository.setLockscreenShadeExpansion(lockscreenShadeExpansion)
    }

    override fun setLockscreenShadeTracking(lockscreenShadeTracking: Boolean) {
        shadeRepository.setLegacyLockscreenShadeTracking(lockscreenShadeTracking)
    }

    override fun setTracking(tracking: Boolean) {
        shadeRepository.setLegacyShadeTracking(tracking)
    }

    override fun assertFlagValid() {
        Assert.assertFalse(SceneContainerFlag.isEnabled)
    }

    /** Sets shade expansion to a value between 0-1. */
    override fun setShadeExpansion(shadeExpansion: Float) {
        shadeRepository.setLegacyShadeExpansion(shadeExpansion)
        testScope.runCurrent()
    }

    /** Sets QS expansion to a value between 0-1. */
    override fun setQsExpansion(qsExpansion: Float) {
        shadeRepository.setQsExpansion(qsExpansion)
        testScope.runCurrent()
    }

    override fun programmaticCollapseShade() {
        shadeRepository.setLegacyShadeExpansion(.5f)
        testScope.runCurrent()
    }

    override fun setQsFullscreen(qsFullscreen: Boolean) {
        shadeRepository.legacyQsFullscreen.value = true
    }

    override fun setLegacyExpandedOrAwaitingInputTransfer(expanded: Boolean) {
        shadeRepository.setLegacyExpandedOrAwaitingInputTransfer(expanded)
    }

    override fun setSplitShade(splitShade: Boolean) {
        context
            .getOrCreateTestableResources()
            .addOverride(R.bool.config_use_split_notification_shade, splitShade)
        testScope.runCurrent()
    }
}

/** Sets up shade state for tests when the scene container flag is enabled. */
class ShadeTestUtilSceneImpl(
    val testScope: TestScope,
    val sceneInteractor: SceneInteractor,
    val shadeRepository: ShadeRepository,
    val context: SysuiTestableContext,
) : ShadeTestUtilDelegate {
    val isUserInputOngoing = MutableStateFlow(true)

    override fun setShadeAndQsExpansion(shadeExpansion: Float, qsExpansion: Float) {
        if (shadeExpansion == 1f) {
            setIdleScene(Scenes.Shade)
        } else if (qsExpansion == 1f) {
            setIdleScene(Scenes.QuickSettings)
        } else if (shadeExpansion == 0f && qsExpansion == 0f) {
            setIdleScene(Scenes.Lockscreen)
        } else if (shadeExpansion == 0f) {
            setTransitionProgress(Scenes.Lockscreen, Scenes.QuickSettings, qsExpansion)
        } else if (qsExpansion == 0f) {
            setTransitionProgress(Scenes.Lockscreen, Scenes.Shade, shadeExpansion)
        } else {
            setTransitionProgress(Scenes.Shade, Scenes.QuickSettings, qsExpansion)
        }
    }

    /** Sets shade expansion to a value between 0-1. */
    override fun setShadeExpansion(shadeExpansion: Float) {
        setShadeAndQsExpansion(shadeExpansion, 0f)
    }

    /** Sets QS expansion to a value between 0-1. */
    override fun setQsExpansion(qsExpansion: Float) {
        setShadeAndQsExpansion(0f, qsExpansion)
    }

    override fun programmaticCollapseShade() {
        setTransitionProgress(Scenes.Shade, Scenes.Lockscreen, .5f, false)
    }

    override fun setQsFullscreen(qsFullscreen: Boolean) {
        setQsExpansion(1f)
    }

    override fun setLegacyExpandedOrAwaitingInputTransfer(
        legacyExpandedOrAwaitingInputTransfer: Boolean
    ) {
        setShadeExpansion(.1f)
    }

    override fun setLockscreenShadeExpansion(lockscreenShadeExpansion: Float) {
        if (lockscreenShadeExpansion == 0f) {
            setIdleScene(Scenes.Lockscreen)
        } else if (lockscreenShadeExpansion == 1f) {
            setIdleScene(Scenes.Shade)
        } else {
            setTransitionProgress(Scenes.Lockscreen, Scenes.Shade, lockscreenShadeExpansion)
        }
    }

    override fun setLockscreenShadeTracking(lockscreenShadeTracking: Boolean) {
        setTracking(lockscreenShadeTracking)
    }

    override fun setTracking(tracking: Boolean) {
        isUserInputOngoing.value = tracking
    }

    private fun setIdleScene(scene: SceneKey) {
        sceneInteractor.changeScene(scene, "test")
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(scene))
        sceneInteractor.setTransitionState(transitionState)
        testScope.runCurrent()
    }

    private fun setTransitionProgress(
        from: SceneKey,
        to: SceneKey,
        progress: Float,
        isInitiatedByUserInput: Boolean = true
    ) {
        sceneInteractor.changeScene(from, "test")
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Transition(
                    fromScene = from,
                    toScene = to,
                    currentScene = flowOf(to),
                    progress = MutableStateFlow(progress),
                    isInitiatedByUserInput = isInitiatedByUserInput,
                    isUserInputOngoing = isUserInputOngoing,
                )
            )
        sceneInteractor.setTransitionState(transitionState)
        testScope.runCurrent()
    }

    override fun setSplitShade(splitShade: Boolean) {
        context
            .getOrCreateTestableResources()
            .addOverride(R.bool.config_use_split_notification_shade, splitShade)
        shadeRepository.setShadeLayoutWide(splitShade)
        testScope.runCurrent()
    }

    override fun assertFlagValid() {
        Assert.assertTrue(SceneContainerFlag.isEnabled)
    }
}
