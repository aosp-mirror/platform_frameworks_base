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

package com.android.systemui.communal.view.viewmodel

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.app.smartspace.SmartspaceTarget
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractorFactory
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalEditModeViewModelTest : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var appWidgetHost: AppWidgetHost

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository

    private lateinit var underTest: CommunalEditModeViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val withDeps = CommunalInteractorFactory.create(testScope)
        keyguardRepository = withDeps.keyguardRepository
        communalRepository = withDeps.communalRepository
        tutorialRepository = withDeps.tutorialRepository
        widgetRepository = withDeps.widgetRepository
        smartspaceRepository = withDeps.smartspaceRepository
        mediaRepository = withDeps.mediaRepository

        underTest =
            CommunalEditModeViewModel(
                withDeps.communalInteractor,
                appWidgetHost,
                Provider { shadeViewController },
                powerManager,
                mediaHost,
            )
    }

    @Test
    fun communalContent_onlyWidgetsAndCtaTileAreShownInEditMode() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
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
                )
            widgetRepository.setCommunalWidgets(widgets)

            // Smartspace available.
            val target = Mockito.mock(SmartspaceTarget::class.java)
            whenever(target.smartspaceTargetId).thenReturn("target")
            whenever(target.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target.remoteViews).thenReturn(Mockito.mock(RemoteViews::class.java))
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(target))

            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)

            // Only Widgets and CTA tile are shown.
            assertThat(communalContent?.size).isEqualTo(3)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.CtaTileInEditMode::class.java)
        }

    @Test
    fun interactionHandlerIgnoresClicks() {
        val interactionHandler = underTest.getInteractionHandler()
        assertThat(
                interactionHandler.onInteraction(
                    /* view = */ mock(),
                    /* pendingIntent = */ mock(),
                    /* response = */ mock()
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun addingWidgetTriggersConfiguration() =
        testScope.runTest {
            val provider = ComponentName("pkg.test", "testWidget")
            val widgetToConfigure by collectLastValue(underTest.widgetsToConfigure)
            assertThat(widgetToConfigure).isNull()
            underTest.onAddWidget(componentName = provider, priority = 0)
            assertThat(widgetToConfigure).isEqualTo(1)
        }

    @Test
    fun settingResultOkAddsWidget() =
        testScope.runTest {
            val provider = ComponentName("pkg.test", "testWidget")
            val widgetAdded by collectLastValue(widgetRepository.widgetAdded)
            assertThat(widgetAdded).isNull()
            underTest.onAddWidget(componentName = provider, priority = 0)
            assertThat(widgetAdded).isNull()
            underTest.setConfigurationResult(RESULT_OK)
            assertThat(widgetAdded).isEqualTo(1)
        }

    @Test
    fun settingResultCancelledDoesNotAddWidget() =
        testScope.runTest {
            val provider = ComponentName("pkg.test", "testWidget")
            val widgetAdded by collectLastValue(widgetRepository.widgetAdded)
            assertThat(widgetAdded).isNull()
            underTest.onAddWidget(componentName = provider, priority = 0)
            assertThat(widgetAdded).isNull()
            underTest.setConfigurationResult(RESULT_CANCELED)
            assertThat(widgetAdded).isNull()
        }

    @Test(expected = IllegalStateException::class)
    fun settingResultBeforeWidgetAddedThrowsException() {
        underTest.setConfigurationResult(RESULT_OK)
    }

    @Test(expected = IllegalStateException::class)
    fun addingWidgetWhileConfigurationActiveFails() =
        testScope.runTest {
            val providerOne = ComponentName("pkg.test", "testWidget")
            underTest.onAddWidget(componentName = providerOne, priority = 0)
            runCurrent()
            val providerTwo = ComponentName("pkg.test", "testWidget2")
            underTest.onAddWidget(componentName = providerTwo, priority = 0)
        }
}
