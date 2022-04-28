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

package com.android.systemui.media

import org.mockito.Mockito.`when` as whenever
import android.animation.Animator
import android.animation.AnimatorSet
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MetadataAnimationHandlerTest : SysuiTestCase() {

    private interface Callback : () -> Unit
    private lateinit var handler: MetadataAnimationHandler

    @Mock private lateinit var animatorSet: AnimatorSet
    @Mock private lateinit var enterAnimator: Animator
    @Mock private lateinit var exitAnimator: Animator
    @Mock private lateinit var postExitCB: Callback
    @Mock private lateinit var postEnterCB: Callback

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        handler = object : MetadataAnimationHandler(exitAnimator, enterAnimator) {
            override fun buildAnimatorSet(exit: Animator, enter: Animator): AnimatorSet {
                return animatorSet
            }
        }
    }

    @After
    fun tearDown() {}

    @Test
    fun firstBind_startsAnimationSet() {
        val cb = { fail("Unexpected callback") }
        handler.setNext("data-1", cb, cb)

        verify(animatorSet).start()
    }

    @Test
    fun executeAnimationEnd_runsCallacks() {
        handler.setNext("data-1", postExitCB, postEnterCB)
        verify(animatorSet, times(1)).start()
        verify(postExitCB, never()).invoke()

        handler.onAnimationEnd(exitAnimator)
        verify(animatorSet, times(1)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postEnterCB, never()).invoke()

        handler.onAnimationEnd(enterAnimator)
        verify(animatorSet, times(1)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postEnterCB, times(1)).invoke()
    }

    @Test
    fun rebindSameData_executesFirstCallback() {
        val postExitCB2 = mock(Callback::class.java)

        handler.setNext("data-1", postExitCB, postEnterCB)
        handler.setNext("data-1", postExitCB2, postEnterCB)
        handler.onAnimationEnd(exitAnimator)

        verify(postExitCB, times(1)).invoke()
        verify(postExitCB2, never()).invoke()
        verify(postEnterCB, never()).invoke()
    }

    @Test
    fun rebindDifferentData_executesSecondCallback() {
        val postExitCB2 = mock(Callback::class.java)

        handler.setNext("data-1", postExitCB, postEnterCB)
        handler.setNext("data-2", postExitCB2, postEnterCB)
        handler.onAnimationEnd(exitAnimator)

        verify(postExitCB, never()).invoke()
        verify(postExitCB2, times(1)).invoke()
        verify(postEnterCB, never()).invoke()
    }

    @Test
    fun rebindBeforeEnterComplete_animationRestarts() {
        val postExitCB2 = mock(Callback::class.java)
        val postEnterCB2 = mock(Callback::class.java)

        handler.setNext("data-1", postExitCB, postEnterCB)
        verify(animatorSet, times(1)).start()
        verify(postExitCB, never()).invoke()
        verify(postExitCB2, never()).invoke()
        verify(postEnterCB, never()).invoke()
        verify(postEnterCB2, never()).invoke()

        whenever(animatorSet.isRunning()).thenReturn(true)
        handler.onAnimationEnd(exitAnimator)
        verify(animatorSet, times(1)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postExitCB2, never()).invoke()
        verify(postEnterCB, never()).invoke()
        verify(postEnterCB2, never()).invoke()

        handler.setNext("data-2", postExitCB2, postEnterCB2)
        handler.onAnimationEnd(enterAnimator)
        verify(animatorSet, times(2)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postExitCB2, never()).invoke()
        verify(postEnterCB, never()).invoke()
        verify(postEnterCB2, never()).invoke()

        handler.onAnimationEnd(exitAnimator)
        verify(animatorSet, times(2)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postExitCB2, times(1)).invoke()
        verify(postEnterCB, never()).invoke()
        verify(postEnterCB2, never()).invoke()

        handler.onAnimationEnd(enterAnimator)
        verify(animatorSet, times(2)).start()
        verify(postExitCB, times(1)).invoke()
        verify(postExitCB2, times(1)).invoke()
        verify(postEnterCB, never()).invoke()
        verify(postEnterCB2, times(1)).invoke()
    }

    @Test
    fun exitAnimationEndMultipleCalls_singleCallbackExecution() {
        handler.setNext("data-1", postExitCB, postEnterCB)
        handler.onAnimationEnd(exitAnimator)
        handler.onAnimationEnd(exitAnimator)
        handler.onAnimationEnd(exitAnimator)

        verify(postExitCB, times(1)).invoke()
    }

    @Test
    fun enterAnimatorEndsWithoutCallback_noAnimatiorStart() {
        handler.onAnimationEnd(enterAnimator)

        verify(animatorSet, never()).start()
    }
}
