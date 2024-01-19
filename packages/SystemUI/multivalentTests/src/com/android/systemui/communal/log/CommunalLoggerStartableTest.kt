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

package com.android.systemui.communal.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalLoggerStartableTest : SysuiTestCase() {
    @Mock private lateinit var uiEventLogger: UiEventLogger

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var communalInteractor: CommunalInteractor
    private lateinit var underTest: CommunalLoggerStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        communalInteractor = kosmos.communalInteractor

        underTest =
            CommunalLoggerStartable(
                testScope.backgroundScope,
                communalInteractor,
                uiEventLogger,
            )
        underTest.start()
    }

    @Test
    fun transitionStateLogging_enterCommunalHub() =
        testScope.runTest {
            // Transition state is default (non-communal)
            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(idle(CommunalSceneKey.DEFAULT))
            communalInteractor.setTransitionState(transitionState)
            runCurrent()

            // Verify nothing is logged from the default state
            verify(uiEventLogger, never()).log(any())

            // Start transition to communal
            transitionState.value = transition(to = CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_START)

            // Finish transition to communal
            transitionState.value = idle(CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_FINISH)
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SHOWN)
        }

    @Test
    fun transitionStateLogging_enterCommunalHub_canceled() =
        testScope.runTest {
            // Transition state is default (non-communal)
            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(idle(CommunalSceneKey.DEFAULT))
            communalInteractor.setTransitionState(transitionState)
            runCurrent()

            // Verify nothing is logged from the default state
            verify(uiEventLogger, never()).log(any())

            // Start transition to communal
            transitionState.value = transition(to = CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_START)

            // Cancel the transition
            transitionState.value = idle(CommunalSceneKey.DEFAULT)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_CANCEL)

            // Verify neither SHOWN nor GONE is logged
            verify(uiEventLogger, never()).log(CommunalUiEvent.COMMUNAL_HUB_SHOWN)
            verify(uiEventLogger, never()).log(CommunalUiEvent.COMMUNAL_HUB_GONE)
        }

    @Test
    fun transitionStateLogging_exitCommunalHub() =
        testScope.runTest {
            // Transition state is communal
            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(idle(CommunalSceneKey.Communal))
            communalInteractor.setTransitionState(transitionState)
            runCurrent()

            // Verify SHOWN is logged when it's the default state
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SHOWN)

            // Start transition from communal
            transitionState.value = transition(from = CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_START)

            // Finish transition to communal
            transitionState.value = idle(CommunalSceneKey.DEFAULT)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_FINISH)
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_GONE)
        }

    @Test
    fun transitionStateLogging_exitCommunalHub_canceled() =
        testScope.runTest {
            // Transition state is communal
            val transitionState =
                MutableStateFlow<ObservableCommunalTransitionState>(idle(CommunalSceneKey.Communal))
            communalInteractor.setTransitionState(transitionState)
            runCurrent()

            // Clear the initial SHOWN event from the logger
            clearInvocations(uiEventLogger)

            // Start transition from communal
            transitionState.value = transition(from = CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_START)

            // Cancel the transition
            transitionState.value = idle(CommunalSceneKey.Communal)
            runCurrent()

            // Verify UiEvent logged
            verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_CANCEL)

            // Verify neither SHOWN nor GONE is logged
            verify(uiEventLogger, never()).log(CommunalUiEvent.COMMUNAL_HUB_SHOWN)
            verify(uiEventLogger, never()).log(CommunalUiEvent.COMMUNAL_HUB_GONE)
        }

    private fun transition(
        from: CommunalSceneKey = CommunalSceneKey.DEFAULT,
        to: CommunalSceneKey = CommunalSceneKey.DEFAULT,
    ): ObservableCommunalTransitionState.Transition {
        return ObservableCommunalTransitionState.Transition(
            fromScene = from,
            toScene = to,
            progress = emptyFlow(),
            isInitiatedByUserInput = true,
            isUserInputOngoing = emptyFlow(),
        )
    }

    private fun idle(sceneKey: CommunalSceneKey): ObservableCommunalTransitionState.Idle {
        return ObservableCommunalTransitionState.Idle(sceneKey)
    }
}
