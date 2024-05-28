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

package com.android.systemui.communal.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.model.sceneDataSource
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalRepositoryImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy {
        CommunalSceneRepositoryImpl(
            kosmos.applicationCoroutineScope,
            kosmos.sceneDataSource,
        )
    }

    @Test
    fun transitionState_idleByDefault() =
        testScope.runTest {
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableTransitionState.Idle(CommunalScenes.Default))
        }

    @Test
    fun transitionState_setTransitionState_returnsNewValue() =
        testScope.runTest {
            val expectedSceneKey = CommunalScenes.Communal
            underTest.setTransitionState(flowOf(ObservableTransitionState.Idle(expectedSceneKey)))

            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState).isEqualTo(ObservableTransitionState.Idle(expectedSceneKey))
        }

    @Test
    fun transitionState_setNullTransitionState_returnsDefaultValue() =
        testScope.runTest {
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
}
