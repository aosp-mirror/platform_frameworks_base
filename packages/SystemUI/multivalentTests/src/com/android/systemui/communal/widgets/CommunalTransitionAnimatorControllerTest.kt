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

package com.android.systemui.communal.widgets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTransitionAnimatorControllerTest : SysuiTestCase() {
    private val controller = mock<ActivityTransitionAnimator.Controller>()
    private val kosmos = testKosmos()

    private lateinit var underTest: CommunalTransitionAnimatorController

    @Before
    fun setUp() {
        with(kosmos) {
            underTest = CommunalTransitionAnimatorController(controller, communalSceneInteractor)
        }
    }

    @Test
    fun doNotAnimate_launchingWidgetStateIsCleared() {
        with(kosmos) {
            testScope.runTest {
                val launching by collectLastValue(communalSceneInteractor.isLaunchingWidget)

                communalSceneInteractor.setIsLaunchingWidget(true)
                assertTrue(launching!!)

                underTest.onIntentStarted(willAnimate = false)
                assertFalse(launching!!)
                verify(controller).onIntentStarted(willAnimate = false)
            }
        }
    }

    @Test
    fun animationCancelled_launchingWidgetStateIsCleared() {
        with(kosmos) {
            testScope.runTest {
                val launching by collectLastValue(communalSceneInteractor.isLaunchingWidget)
                val scene by collectLastValue(communalSceneInteractor.currentScene)

                communalSceneInteractor.changeScene(CommunalScenes.Communal, "test")
                Truth.assertThat(scene).isEqualTo(CommunalScenes.Communal)
                communalSceneInteractor.setIsLaunchingWidget(true)
                assertTrue(launching!!)

                underTest.onIntentStarted(willAnimate = true)
                assertTrue(launching!!)
                verify(controller).onIntentStarted(willAnimate = true)

                underTest.onTransitionAnimationStart(isExpandingFullyAbove = true)
                assertTrue(launching!!)
                verify(controller).onTransitionAnimationStart(isExpandingFullyAbove = true)

                underTest.onTransitionAnimationCancelled(newKeyguardOccludedState = true)
                assertFalse(launching!!)
                verify(controller).onTransitionAnimationCancelled(newKeyguardOccludedState = true)
            }
        }
    }

    @Test
    fun animationComplete_launchingWidgetStateIsClearedAndSceneIsChanged() {
        with(kosmos) {
            testScope.runTest {
                val launching by collectLastValue(communalSceneInteractor.isLaunchingWidget)
                val scene by collectLastValue(communalSceneInteractor.currentScene)

                communalSceneInteractor.changeScene(CommunalScenes.Communal, "test")
                Truth.assertThat(scene).isEqualTo(CommunalScenes.Communal)
                communalSceneInteractor.setIsLaunchingWidget(true)
                assertTrue(launching!!)

                underTest.onIntentStarted(willAnimate = true)
                assertTrue(launching!!)
                verify(controller).onIntentStarted(willAnimate = true)

                underTest.onTransitionAnimationStart(isExpandingFullyAbove = true)
                assertTrue(launching!!)
                verify(controller).onTransitionAnimationStart(isExpandingFullyAbove = true)

                testScope.advanceTimeBy(ActivityTransitionAnimator.TIMINGS.totalDuration)

                underTest.onTransitionAnimationEnd(isExpandingFullyAbove = true)
                assertFalse(launching!!)
                Truth.assertThat(scene).isEqualTo(CommunalScenes.Blank)
                verify(controller).onTransitionAnimationEnd(isExpandingFullyAbove = true)
            }
        }
    }
}
