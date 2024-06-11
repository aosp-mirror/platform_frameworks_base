/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.animation

import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AnimationBindHandlerTest : SysuiTestCase() {

    private interface Callback : () -> Unit
    private abstract class AnimatedDrawable : Drawable(), Animatable2
    private lateinit var handler: AnimationBindHandler

    @Mock private lateinit var animatable: AnimatedDrawable
    @Mock private lateinit var animatable2: AnimatedDrawable
    @Mock private lateinit var callback: Callback

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        handler = AnimationBindHandler()
    }

    @After fun tearDown() {}

    @Test
    fun registerNoAnimations_executeCallbackImmediately() {
        handler.tryExecute(callback)
        verify(callback).invoke()
    }

    @Test
    fun registerStoppedAnimations_executeCallbackImmediately() {
        whenever(animatable.isRunning).thenReturn(false)
        whenever(animatable2.isRunning).thenReturn(false)

        handler.tryExecute(callback)
        verify(callback).invoke()
    }

    @Test
    fun registerRunningAnimations_executeCallbackDelayed() {
        whenever(animatable.isRunning).thenReturn(true)
        whenever(animatable2.isRunning).thenReturn(true)

        handler.tryRegister(animatable)
        handler.tryRegister(animatable2)
        handler.tryExecute(callback)

        verify(callback, never()).invoke()

        whenever(animatable.isRunning).thenReturn(false)
        handler.onAnimationEnd(animatable)
        verify(callback, never()).invoke()

        whenever(animatable2.isRunning).thenReturn(false)
        handler.onAnimationEnd(animatable2)
        verify(callback, times(1)).invoke()
    }

    @Test
    fun repeatedEndCallback_executeSingleCallback() {
        whenever(animatable.isRunning).thenReturn(true)

        handler.tryRegister(animatable)
        handler.tryExecute(callback)

        verify(callback, never()).invoke()

        whenever(animatable.isRunning).thenReturn(false)
        handler.onAnimationEnd(animatable)
        handler.onAnimationEnd(animatable)
        handler.onAnimationEnd(animatable)
        verify(callback, times(1)).invoke()
    }

    @Test
    fun registerUnregister_executeImmediately() {
        whenever(animatable.isRunning).thenReturn(true)

        handler.tryRegister(animatable)
        handler.unregisterAll()
        handler.tryExecute(callback)

        verify(callback).invoke()
    }

    @Test
    fun updateRebindId_returnsAsExpected() {
        // Previous or current call is null, returns true
        assertTrue(handler.updateRebindId(null))
        assertTrue(handler.updateRebindId(null))
        assertTrue(handler.updateRebindId(null))
        assertTrue(handler.updateRebindId(10))
        assertTrue(handler.updateRebindId(null))
        assertTrue(handler.updateRebindId(20))

        // Different integer from prevoius, returns true
        assertTrue(handler.updateRebindId(10))
        assertTrue(handler.updateRebindId(20))

        // Matches previous call, returns false
        assertFalse(handler.updateRebindId(20))
        assertFalse(handler.updateRebindId(20))
        assertTrue(handler.updateRebindId(10))
        assertFalse(handler.updateRebindId(10))
    }
}
