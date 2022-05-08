/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUnfoldTransition.Companion.LEFT
import com.android.keyguard.KeyguardUnfoldTransition.Companion.RIGHT
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.util.mockito.capture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter fn.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardUnfoldTransitionTest : SysuiTestCase() {

    @Mock
    private lateinit var progressProvider: NaturalRotationUnfoldProgressProvider

    @Captor
    private lateinit var progressListenerCaptor: ArgumentCaptor<TransitionProgressListener>

    @Mock
    private lateinit var parent: ViewGroup

    private lateinit var keyguardUnfoldTransition: KeyguardUnfoldTransition
    private lateinit var progressListener: TransitionProgressListener
    private var xTranslationMax = 0f

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        xTranslationMax = context.resources.getDimensionPixelSize(
            R.dimen.keyguard_unfold_translation_x).toFloat()

        keyguardUnfoldTransition = KeyguardUnfoldTransition(
            getContext(),
            progressProvider
        )

        verify(progressProvider).addCallback(capture(progressListenerCaptor))
        progressListener = progressListenerCaptor.value

        keyguardUnfoldTransition.setup(parent)
        keyguardUnfoldTransition.statusViewCentered = false
    }

    @Test
    fun onTransition_noMatchingIds() {
        // GIVEN no views matching any ids
        // WHEN the transition starts
        progressListener.onTransitionStarted()
        progressListener.onTransitionProgress(.1f)

        // THEN nothing... no exceptions
    }

    @Test
    fun onTransition_oneMovesLeft() {
        // GIVEN one view with a matching id
        val view = View(getContext())
        `when`(parent.findViewById<View>(R.id.keyguard_status_area)).thenReturn(view)

        moveAndValidate(listOf(view to LEFT))
    }

    @Test
    fun onTransition_oneMovesLeftAndOneMovesRightMultipleTimes() {
        // GIVEN two views with a matching id
        val leftView = View(getContext())
        val rightView = View(getContext())
        `when`(parent.findViewById<View>(R.id.keyguard_status_area)).thenReturn(leftView)
        `when`(parent.findViewById<View>(R.id.notification_stack_scroller)).thenReturn(rightView)

        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
    }

    @Test
    fun onTransition_centeredViewDoesNotMove() {
        keyguardUnfoldTransition.statusViewCentered = true

        val view = View(getContext())
        `when`(parent.findViewById<View>(R.id.lockscreen_clock_view_large)).thenReturn(view)

        moveAndValidate(listOf(view to 0))
    }

    private fun moveAndValidate(list: List<Pair<View, Int>>) {
        // Compare values as ints because -0f != 0f

        // WHEN the transition starts
        progressListener.onTransitionStarted()
        progressListener.onTransitionProgress(0f)

        list.forEach { (view, direction) ->
            assertEquals((-xTranslationMax * direction).toInt(), view.getTranslationX().toInt())
        }

        // WHEN the transition progresses, translation is updated
        progressListener.onTransitionProgress(.5f)
        list.forEach { (view, direction) ->
            assertEquals(
                (-xTranslationMax / 2f * direction).toInt(),
                view.getTranslationX().toInt()
            )
        }

        // WHEN the transition ends, translation is completed
        progressListener.onTransitionProgress(1f)
        progressListener.onTransitionFinished()
        list.forEach { (view, _) ->
            assertEquals(0, view.getTranslationX().toInt())
        }
    }
}
