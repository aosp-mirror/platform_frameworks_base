/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.shared.model.SceneDataSource
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSceneRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val delegator = mock<SceneDataSourceDelegator> {}

    private val Kosmos.underTest by
        Kosmos.Fixture {
            CommunalSceneRepositoryImpl(
                applicationScope = applicationCoroutineScope,
                backgroundScope = backgroundScope,
                sceneDataSource = delegator,
                delegator = delegator,
            )
        }

    @Test
    fun transitionState_idleByDefault() =
        kosmos.runTest {
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableTransitionState.Idle(CommunalScenes.Default))
        }

    @Test
    fun transitionState_setTransitionState_returnsNewValue() =
        kosmos.runTest {
            val expectedSceneKey = CommunalScenes.Communal
            underTest.setTransitionState(flowOf(ObservableTransitionState.Idle(expectedSceneKey)))

            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState).isEqualTo(ObservableTransitionState.Idle(expectedSceneKey))
        }

    @Test
    fun transitionState_setNullTransitionState_returnsDefaultValue() =
        kosmos.runTest {
            // Set a value for the transition state flow.
            underTest.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )

            // Set the transition state flow back to null.
            underTest.setTransitionState(null)

            // Flow returns default scene key.
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableTransitionState.Idle(CommunalScenes.Default))
        }

    @Test
    fun showHubFromPowerButton() =
        kosmos.runTest {
            fakeKeyguardRepository.setKeyguardShowing(false)

            underTest.showHubFromPowerButton()

            argumentCaptor<SceneDataSource>().apply {
                verify(delegator).setDelegate(capture())

                assertThat(firstValue.currentScene.value).isEqualTo(CommunalScenes.Communal)
            }
        }
}
