/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.animation

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class UnfoldConstantTranslateAnimatorTest : SysuiTestCase() {

    private val progressProvider = FakeUnfoldTransitionProvider()

    @Mock private lateinit var parent: ViewGroup

    @Mock private lateinit var shouldBeAnimated: () -> Boolean

    private lateinit var animator: UnfoldConstantTranslateAnimator

    private val viewsIdToRegister
        get() =
            setOf(
                ViewIdToTranslate(START_VIEW_ID, Direction.START, shouldBeAnimated),
                ViewIdToTranslate(END_VIEW_ID, Direction.END, shouldBeAnimated)
            )

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(shouldBeAnimated.invoke()).thenReturn(true)
        animator = UnfoldConstantTranslateAnimator(viewsIdToRegister, progressProvider)

        animator.init(parent, MAX_TRANSLATION)
    }

    @Test
    fun onTransition_noMatchingIds() {
        // GIVEN no views matching any ids
        // WHEN the transition starts
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(.1f)

        // THEN nothing... no exceptions
    }

    @Test
    fun onTransition_oneMovesStartWithLTR() {
        // GIVEN one view with a matching id
        val view = View(context)
        whenever(parent.findViewById<View>(START_VIEW_ID)).thenReturn(view)

        moveAndValidate(listOf(view to START), View.LAYOUT_DIRECTION_LTR)
    }

    @Test
    fun initMultipleTimes_onTransition_translationIsSetOnlyOnce() {
        animator.init(parent, MAX_TRANSLATION)
        animator.init(parent, MAX_TRANSLATION)
        animator.init(parent, MAX_TRANSLATION)

        // GIVEN one view with a matching id
        val view = spy(View(context))
        whenever(parent.findViewById<View>(START_VIEW_ID)).thenReturn(view)
        progressProvider.onTransitionStarted()

        // WHEN the transition progresses, translation is updated once
        progressProvider.onTransitionProgress(.5f)
        verify(view).translationX = anyFloat()
    }

    @Test
    fun onTransition_oneMovesStartWithRTL() {
        // GIVEN one view with a matching id
        val view = View(context)
        whenever(parent.findViewById<View>(START_VIEW_ID)).thenReturn(view)

        whenever(parent.getLayoutDirection()).thenReturn(View.LAYOUT_DIRECTION_RTL)
        moveAndValidate(listOf(view to START), View.LAYOUT_DIRECTION_RTL)
    }

    @Test
    fun onTransition_oneMovesStartAndOneMovesEndMultipleTimes() {
        // GIVEN two views with a matching id
        val leftView = View(context)
        val rightView = View(context)
        whenever(parent.findViewById<View>(START_VIEW_ID)).thenReturn(leftView)
        whenever(parent.findViewById<View>(END_VIEW_ID)).thenReturn(rightView)

        moveAndValidate(listOf(leftView to START, rightView to END), View.LAYOUT_DIRECTION_LTR)
        moveAndValidate(listOf(leftView to START, rightView to END), View.LAYOUT_DIRECTION_LTR)
    }

    @Test
    fun onTransition_completeStartedTranslation() {
        // GIVEN
        val leftView = View(context)
        whenever(parent.findViewById<View>(START_VIEW_ID)).thenReturn(leftView)
        // To start animation, shouldBeAnimated should return true.
        // There is a possibility for shouldBeAnimated to return false during the animation.
        whenever(shouldBeAnimated.invoke()).thenReturn(true).thenReturn(false)

        // shouldBeAnimated state may change during the animation.
        // However, started animation should be completed.
        moveAndValidate(listOf(leftView to START), View.LAYOUT_DIRECTION_LTR)
    }

    private fun moveAndValidate(list: List<Pair<View, Int>>, layoutDirection: Int) {
        // Compare values as ints because -0f != 0f

        // WHEN the transition starts
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0f)

        val rtlMultiplier =
            if (layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                1
            } else {
                -1
            }
        list.forEach { (view, direction) ->
            assertEquals(
                (-MAX_TRANSLATION * direction * rtlMultiplier).toInt(),
                view.translationX.toInt()
            )
        }

        // WHEN the transition progresses, translation is updated
        progressProvider.onTransitionProgress(.5f)
        list.forEach { (view, direction) ->
            assertEquals(
                (-MAX_TRANSLATION / 2f * direction * rtlMultiplier).toInt(),
                view.translationX.toInt()
            )
        }

        // WHEN the transition ends, translation is completed
        progressProvider.onTransitionProgress(1f)
        progressProvider.onTransitionFinished()
        list.forEach { (view, _) -> assertEquals(0, view.translationX.toInt()) }
    }

    companion object {
        private val START = Direction.START.multiplier.toInt()
        private val END = Direction.END.multiplier.toInt()

        private const val MAX_TRANSLATION = 42f

        private const val START_VIEW_ID = 1
        private const val END_VIEW_ID = 2
    }
}
