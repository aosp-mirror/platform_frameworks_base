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
 *
 */

package com.android.systemui.communal.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.shared.model.CommunalAppWidgetInfo
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalInteractorTest : SysuiTestCase() {
    @Mock private lateinit var stopwatchAppWidgetInfo: CommunalAppWidgetInfo

    private lateinit var testScope: TestScope

    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var interactor: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()
        communalRepository = FakeCommunalRepository()
        widgetRepository = FakeCommunalWidgetRepository()
        mediaRepository = FakeCommunalMediaRepository()
        interactor = CommunalInteractor(communalRepository, widgetRepository, mediaRepository)
    }

    @Test
    fun appWidgetInfoFlow() =
        testScope.runTest {
            val lastAppWidgetInfo = collectLastValue(interactor.appWidgetInfo)
            runCurrent()
            assertThat(lastAppWidgetInfo()).isNull()

            widgetRepository.setStopwatchAppWidgetInfo(stopwatchAppWidgetInfo)
            runCurrent()
            assertThat(lastAppWidgetInfo()).isEqualTo(stopwatchAppWidgetInfo)
        }

    @Test
    fun communalEnabled() =
        testScope.runTest {
            communalRepository.setIsCommunalEnabled(true)

            val interactor =
                CommunalInteractor(communalRepository, widgetRepository, mediaRepository)
            assertThat(interactor.isCommunalEnabled).isTrue()
        }

    @Test
    fun communalDisabled() =
        testScope.runTest {
            communalRepository.setIsCommunalEnabled(false)

            val interactor =
                CommunalInteractor(communalRepository, widgetRepository, mediaRepository)
            assertThat(interactor.isCommunalEnabled).isFalse()
        }

    @Test
    fun listensToSceneChange() =
        testScope.runTest {
            val interactor =
                CommunalInteractor(communalRepository, widgetRepository, mediaRepository)
            var desiredScene = collectLastValue(interactor.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(CommunalSceneKey.Blank)

            val targetScene = CommunalSceneKey.Communal
            communalRepository.setDesiredScene(targetScene)
            desiredScene = collectLastValue(interactor.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        testScope.runTest {
            val interactor =
                CommunalInteractor(communalRepository, widgetRepository, mediaRepository)
            val targetScene = CommunalSceneKey.Communal

            interactor.onSceneChanged(targetScene)

            val desiredScene = collectLastValue(communalRepository.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun isCommunalShowing() =
        testScope.runTest {
            val interactor =
                CommunalInteractor(communalRepository, widgetRepository, mediaRepository)

            var isCommunalShowing = collectLastValue(interactor.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(false)

            interactor.onSceneChanged(CommunalSceneKey.Communal)

            isCommunalShowing = collectLastValue(interactor.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(true)
        }
}
