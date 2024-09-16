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

package com.android.systemui.util.drawable

import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@MediumTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class LoopedAnimatable2DrawableWrapperTest : SysuiTestCase() {

    @Mock private lateinit var drawable: AnimatedDrawable
    @Captor private lateinit var callbackCaptor: ArgumentCaptor<Animatable2.AnimationCallback>

    private lateinit var underTest: LoopedAnimatable2DrawableWrapper

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest = LoopedAnimatable2DrawableWrapper.fromDrawable(drawable)
    }

    @Test
    fun startAddsTheCallback() {
        underTest.start()

        verify(drawable).registerAnimationCallback(any())
    }

    @Test
    fun multipleStartAddsTheCallbackOnce() {
        underTest.start()
        underTest.start()
        underTest.start()
        underTest.start()

        verify(drawable).registerAnimationCallback(any())
    }

    @Test
    fun stopRemovesTheCallback() {
        underTest.start()

        underTest.stop()

        verify(drawable).unregisterAnimationCallback(any())
    }

    @Test
    fun callbackSurvivesClearAnimationCallbacks() {
        underTest.start()

        underTest.clearAnimationCallbacks()

        verify(drawable).clearAnimationCallbacks()
        // start + re-add after #clearAnimationCallbacks
        verify(drawable, times(2)).registerAnimationCallback(capture(callbackCaptor))
    }

    @Test
    fun animationLooped() {
        underTest.start()
        verify(drawable).registerAnimationCallback(capture(callbackCaptor))

        callbackCaptor.value.onAnimationEnd(drawable)

        // underTest.start() + looped start()
        verify(drawable, times(2)).start()
    }

    private abstract class AnimatedDrawable : Drawable(), Animatable2
}
