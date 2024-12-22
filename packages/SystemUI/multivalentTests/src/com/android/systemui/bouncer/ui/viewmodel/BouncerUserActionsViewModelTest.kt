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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.testKosmos
import com.android.systemui.truth.containsEntriesExactly
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
@EnableSceneContainer
class BouncerUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: BouncerUserActionsViewModel

    @Before
    fun setUp() {
        kosmos.sceneContainerStartable.start()
        underTest = kosmos.bouncerUserActionsViewModel
        underTest.activateIn(testScope)
    }

    @Test
    fun actions() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            kosmos.fakeSceneDataSource.changeScene(Scenes.QuickSettings)
            runCurrent()

            kosmos.fakeSceneDataSource.changeScene(Scenes.Bouncer)
            runCurrent()

            assertThat(actions)
                .containsEntriesExactly(
                    Back to UserActionResult(Scenes.QuickSettings),
                    Swipe(SwipeDirection.Down) to UserActionResult(Scenes.QuickSettings),
                )
        }
}
