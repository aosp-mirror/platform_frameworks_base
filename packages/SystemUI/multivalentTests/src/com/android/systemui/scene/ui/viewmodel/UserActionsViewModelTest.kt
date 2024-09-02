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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class UserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = FakeUserActionsViewModel()

    @Test
    fun actions_emptyBeforeActivation() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)

            assertThat(actions).isEmpty()
        }

    @Test
    fun actions_emptyBeforeFirstValue() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            underTest.activateIn(testScope)
            runCurrent()

            assertThat(actions).isEmpty()
        }

    @Test
    fun actions() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            underTest.activateIn(testScope)
            runCurrent()

            val expected1 =
                mapOf(
                    Back to UserActionResult(toScene = Scenes.Gone),
                    Swipe(SwipeDirection.Up) to UserActionResult(toScene = Scenes.Shade)
                )
            underTest.upstream.value = expected1
            runCurrent()
            assertThat(actions).isEqualTo(expected1)

            val expected2 =
                mapOf(
                    Back to UserActionResult(toScene = Scenes.Lockscreen),
                    Swipe(SwipeDirection.Down) to UserActionResult(toScene = Scenes.Shade)
                )
            underTest.upstream.value = expected2
            runCurrent()
            assertThat(actions).isEqualTo(expected2)
        }

    @Test
    fun actions_emptyAfterCancellation() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val job = Job()
            underTest.activateIn(testScope, job)
            runCurrent()

            val expected =
                mapOf(
                    Back to UserActionResult(toScene = Scenes.Lockscreen),
                    Swipe(SwipeDirection.Down) to UserActionResult(toScene = Scenes.Shade)
                )
            underTest.upstream.value = expected
            runCurrent()
            assertThat(actions).isEqualTo(expected)

            job.cancel()
            runCurrent()
            assertThat(actions).isEmpty()
        }

    private class FakeUserActionsViewModel : UserActionsViewModel() {

        val upstream = MutableStateFlow<Map<UserAction, UserActionResult>>(emptyMap())

        override suspend fun hydrateActions(
            setActions: (Map<UserAction, UserActionResult>) -> Unit,
        ) {
            upstream.collect { setActions(it) }
        }
    }
}
