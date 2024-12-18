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

package com.android.systemui.scene.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class GoneUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val shadeRepository by lazy { kosmos.shadeRepository }
    private lateinit var underTest: GoneUserActionsViewModel

    @Before
    fun setUp() {
        underTest = GoneUserActionsViewModel(shadeInteractor = kosmos.shadeInteractor)
        underTest.activateIn(testScope)
    }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun downTransitionKey_splitShadeEnabled_isGoneToSplitShade() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(true)
            runCurrent()

            assertThat(userActions?.get(Swipe(SwipeDirection.Down))?.transitionKey)
                .isEqualTo(ToSplitShade)
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun downTransitionKey_splitShadeDisabled_isNull() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(false)
            runCurrent()

            assertThat(userActions?.get(Swipe(SwipeDirection.Down))?.transitionKey).isNull()
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun downTransitionKey_dualShadeEnabled_isNull() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(true)
            runCurrent()

            assertThat(userActions?.get(Swipe(SwipeDirection.Down))?.transitionKey).isNull()
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun swipeDownWithTwoFingers_singleShade_goesToQuickSettings() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(false)
            runCurrent()

            assertThat(userActions?.get(swipeDownFromTopWithTwoFingers()))
                .isEqualTo(UserActionResult(Scenes.QuickSettings))
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun swipeDownWithTwoFingers_splitShade_goesToShade() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(true)
            runCurrent()

            assertThat(userActions?.get(swipeDownFromTopWithTwoFingers()))
                .isEqualTo(UserActionResult(Scenes.Shade, ToSplitShade))
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun swipeDownWithTwoFingers_dualShadeEnabled_isNull() =
        testScope.runTest {
            val userActions by collectLastValue(underTest.actions)
            runCurrent()

            assertThat(userActions?.get(swipeDownFromTopWithTwoFingers())).isNull()
        }

    private fun swipeDownFromTopWithTwoFingers(): UserAction {
        return Swipe(direction = SwipeDirection.Down, pointerCount = 2, fromSource = Edge.Top)
    }
}
