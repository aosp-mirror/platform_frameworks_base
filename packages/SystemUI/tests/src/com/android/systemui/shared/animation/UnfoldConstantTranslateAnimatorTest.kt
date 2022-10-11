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

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class UnfoldConstantTranslateAnimatorTest : SysuiTestCase() {

    private val progressProvider = TestUnfoldTransitionProvider()

    @Mock private lateinit var parent: ViewGroup

    private lateinit var animator: UnfoldConstantTranslateAnimator

    private val viewsIdToRegister =
        setOf(
            ViewIdToTranslate(LEFT_VIEW_ID, Direction.LEFT),
            ViewIdToTranslate(RIGHT_VIEW_ID, Direction.RIGHT))

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        animator =
            UnfoldConstantTranslateAnimator(viewsIdToRegister, progressProvider)

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
    fun onTransition_oneMovesLeft() {
        // GIVEN one view with a matching id
        val view = View(context)
        whenever(parent.findViewById<View>(LEFT_VIEW_ID)).thenReturn(view)

        moveAndValidate(listOf(view to LEFT))
    }

    @Test
    fun onTransition_oneMovesLeftAndOneMovesRightMultipleTimes() {
        // GIVEN two views with a matching id
        val leftView = View(context)
        val rightView = View(context)
        whenever(parent.findViewById<View>(LEFT_VIEW_ID)).thenReturn(leftView)
        whenever(parent.findViewById<View>(RIGHT_VIEW_ID)).thenReturn(rightView)

        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
        moveAndValidate(listOf(leftView to LEFT, rightView to RIGHT))
    }

    private fun moveAndValidate(list: List<Pair<View, Int>>) {
        // Compare values as ints because -0f != 0f

        // WHEN the transition starts
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0f)

        list.forEach { (view, direction) ->
            assertEquals((-MAX_TRANSLATION * direction).toInt(), view.translationX.toInt())
        }

        // WHEN the transition progresses, translation is updated
        progressProvider.onTransitionProgress(.5f)
        list.forEach { (view, direction) ->
            assertEquals((-MAX_TRANSLATION / 2f * direction).toInt(), view.translationX.toInt())
        }

        // WHEN the transition ends, translation is completed
        progressProvider.onTransitionProgress(1f)
        progressProvider.onTransitionFinished()
        list.forEach { (view, _) -> assertEquals(0, view.translationX.toInt()) }
    }

    companion object {
        private val LEFT = Direction.LEFT.multiplier.toInt()
        private val RIGHT = Direction.RIGHT.multiplier.toInt()

        private const val MAX_TRANSLATION = 42f

        private const val LEFT_VIEW_ID = 1
        private const val RIGHT_VIEW_ID = 2
    }
}
