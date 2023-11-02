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

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalAppWidgetInfo
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.util.mockito.mock
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

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()

        val withDeps = CommunalInteractorFactory.create()

        tutorialRepository = withDeps.tutorialRepository
        communalRepository = withDeps.communalRepository
        widgetRepository = withDeps.widgetRepository
        keyguardRepository = withDeps.keyguardRepository

        underTest = withDeps.communalInteractor
    }

    @Test
    fun appWidgetInfoFlow() =
        testScope.runTest {
            val lastAppWidgetInfo = collectLastValue(underTest.appWidgetInfo)
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
            assertThat(underTest.isCommunalEnabled).isTrue()
        }

    @Test
    fun communalDisabled() =
        testScope.runTest {
            communalRepository.setIsCommunalEnabled(false)
            assertThat(underTest.isCommunalEnabled).isFalse()
        }

    @Test
    fun tutorial_tutorialNotCompletedAndKeyguardVisible_showTutorialContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial not started.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(
                Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
            )

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent!!).isNotEmpty()
            communalContent!!.forEach { model ->
                assertThat(model is CommunalContentModel.Tutorial).isTrue()
            }
        }

    @Test
    fun widget_tutorialCompletedAndWidgetsAvailable_showWidgetContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets are available.
            val widgets =
                listOf(
                    CommunalWidgetContentModel(
                        appWidgetId = 0,
                        priority = 30,
                        providerInfo = mock(),
                    ),
                    CommunalWidgetContentModel(
                        appWidgetId = 1,
                        priority = 20,
                        providerInfo = mock(),
                    ),
                    CommunalWidgetContentModel(
                        appWidgetId = 2,
                        priority = 10,
                        providerInfo = mock(),
                    ),
                )
            widgetRepository.setCommunalWidgets(widgets)

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent!!).isNotEmpty()
            communalContent!!.forEachIndexed { index, model ->
                assertThat((model as CommunalContentModel.Widget).appWidgetId)
                    .isEqualTo(widgets[index].appWidgetId)
            }
        }

    @Test
    fun listensToSceneChange() =
        testScope.runTest {
            var desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(CommunalSceneKey.Blank)

            val targetScene = CommunalSceneKey.Communal
            communalRepository.setDesiredScene(targetScene)
            desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        testScope.runTest {
            val targetScene = CommunalSceneKey.Communal

            underTest.onSceneChanged(targetScene)

            val desiredScene = collectLastValue(communalRepository.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun isCommunalShowing() =
        testScope.runTest {
            var isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(false)

            underTest.onSceneChanged(CommunalSceneKey.Communal)

            isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(true)
        }
}
