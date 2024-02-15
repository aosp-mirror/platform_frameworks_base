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
 */
package com.android.dream.lowlight

import android.animation.Animator
import android.app.DreamManager
import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import src.com.android.dream.lowlight.utils.any
import src.com.android.dream.lowlight.utils.withArgCaptor

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class LowLightDreamManagerTest {
    @Mock
    private lateinit var mDreamManager: DreamManager
    @Mock
    private lateinit var mEnterAnimator: Animator
    @Mock
    private lateinit var mExitAnimator: Animator

    private lateinit var mTransitionCoordinator: LowLightTransitionCoordinator
    private lateinit var mLowLightDreamManager: LowLightDreamManager
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope(StandardTestDispatcher())

        mTransitionCoordinator = LowLightTransitionCoordinator()
        mTransitionCoordinator.setLowLightEnterListener(
            object : LowLightTransitionCoordinator.LowLightEnterListener {
                override fun onBeforeEnterLowLight() = mEnterAnimator
            })
        mTransitionCoordinator.setLowLightExitListener(
            object : LowLightTransitionCoordinator.LowLightExitListener {
                override fun onBeforeExitLowLight() = mExitAnimator
            })

        mLowLightDreamManager = LowLightDreamManager(
            coroutineScope = testScope,
            dreamManager = mDreamManager,
            lowLightTransitionCoordinator = mTransitionCoordinator,
            lowLightDreamComponent = DREAM_COMPONENT,
            lowLightTransitionTimeoutMs = LOW_LIGHT_TIMEOUT_MS
        )
    }

    @Test
    fun setAmbientLightMode_lowLight_setSystemDream() = testScope.runTest {
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        runCurrent()
        verify(mDreamManager, never()).setSystemDreamComponent(DREAM_COMPONENT)
        completeEnterAnimations()
        runCurrent()
        verify(mDreamManager).setSystemDreamComponent(DREAM_COMPONENT)
    }

    @Test
    fun setAmbientLightMode_regularLight_clearSystemDream() = testScope.runTest {
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR)
        runCurrent()
        verify(mDreamManager, never()).setSystemDreamComponent(null)
        completeExitAnimations()
        runCurrent()
        verify(mDreamManager).setSystemDreamComponent(null)
    }

    @Test
    fun setAmbientLightMode_defaultUnknownMode_clearSystemDream() = testScope.runTest {
        // Set to low light first.
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        runCurrent()
        completeEnterAnimations()
        runCurrent()
        verify(mDreamManager).setSystemDreamComponent(DREAM_COMPONENT)
        clearInvocations(mDreamManager)

        // Return to default unknown mode.
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_UNKNOWN)
        runCurrent()
        completeExitAnimations()
        runCurrent()
        verify(mDreamManager).setSystemDreamComponent(null)
    }

    @Test
    fun setAmbientLightMode_dreamComponentNotSet_doNothing() = testScope.runTest {
        val lowLightDreamManager = LowLightDreamManager(
            coroutineScope = testScope,
            dreamManager = mDreamManager,
            lowLightTransitionCoordinator = mTransitionCoordinator,
            lowLightDreamComponent = null,
            lowLightTransitionTimeoutMs = LOW_LIGHT_TIMEOUT_MS
        )
        lowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        runCurrent()
        verify(mEnterAnimator, never()).addListener(any())
        verify(mDreamManager, never()).setSystemDreamComponent(any())
    }

    @Test
    fun setAmbientLightMode_multipleTimesBeforeAnimationEnds_cancelsPrevious() = testScope.runTest {
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        runCurrent()
        // If we reset the light mode back to regular before the previous animation finishes, it
        // should be ignored.
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR)
        runCurrent()
        completeEnterAnimations()
        completeExitAnimations()
        runCurrent()
        verify(mDreamManager, times(1)).setSystemDreamComponent(null)
    }

    @Test
    fun setAmbientLightMode_animatorNeverFinishes_timesOut() = testScope.runTest {
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        advanceTimeBy(delayTimeMillis = LOW_LIGHT_TIMEOUT_MS + 1)
        // Animation never finishes, but we should still set the system dream
        verify(mDreamManager).setSystemDreamComponent(DREAM_COMPONENT)
    }

    @Test
    fun setAmbientLightMode_animationCancelled_SetsSystemDream() = testScope.runTest {
        mLowLightDreamManager.setAmbientLightMode(LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT)
        runCurrent()
        cancelEnterAnimations()
        runCurrent()
        // Animation never finishes, but we should still set the system dream
        verify(mDreamManager).setSystemDreamComponent(DREAM_COMPONENT)
    }

    private fun cancelEnterAnimations() {
        val listener = withArgCaptor { verify(mEnterAnimator).addListener(capture()) }
        listener.onAnimationCancel(mEnterAnimator)
    }

    private fun completeEnterAnimations() {
        val listener = withArgCaptor { verify(mEnterAnimator).addListener(capture()) }
        listener.onAnimationEnd(mEnterAnimator)
    }

    private fun completeExitAnimations() {
        val listener = withArgCaptor { verify(mExitAnimator).addListener(capture()) }
        listener.onAnimationEnd(mExitAnimator)
    }

    companion object {
        private val DREAM_COMPONENT = ComponentName("test_package", "test_dream")
        private const val LOW_LIGHT_TIMEOUT_MS: Long = 1000
    }
}