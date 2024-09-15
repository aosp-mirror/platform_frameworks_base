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

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
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
class GoneSceneViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val shadeRepository by lazy { kosmos.shadeRepository }
    private lateinit var underTest: GoneSceneViewModel

    @Before
    fun setUp() {
        underTest =
            GoneSceneViewModel(
                applicationScope = testScope.backgroundScope,
                shadeInteractor = kosmos.shadeInteractor,
            )
    }

    @Test
    fun downTransitionKey_splitShadeEnabled_isGoneToSplitShade() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            shadeRepository.setShadeMode(ShadeMode.Split)
            runCurrent()

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Down))?.transitionKey)
                .isEqualTo(ToSplitShade)
        }

    @Test
    fun downTransitionKey_splitShadeDisabled_isNull() =
        testScope.runTest {
            val destinationScenes by collectLastValue(underTest.destinationScenes)
            shadeRepository.setShadeMode(ShadeMode.Single)
            runCurrent()

            assertThat(destinationScenes?.get(Swipe(SwipeDirection.Down))?.transitionKey).isNull()
        }
}
