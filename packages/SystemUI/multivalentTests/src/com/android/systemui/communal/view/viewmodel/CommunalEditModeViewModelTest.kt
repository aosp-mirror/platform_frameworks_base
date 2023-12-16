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

import android.app.smartspace.SmartspaceTarget
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
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalEditModeViewModelTest : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var powerManager: PowerManager

    private lateinit var testScope: TestScope

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

        testScope = TestScope()

        val withDeps = CommunalInteractorFactory.create()
        keyguardRepository = withDeps.keyguardRepository
        communalRepository = withDeps.communalRepository
        tutorialRepository = withDeps.tutorialRepository
        widgetRepository = withDeps.widgetRepository
        smartspaceRepository = withDeps.smartspaceRepository
        mediaRepository = withDeps.mediaRepository

        underTest =
            CommunalEditModeViewModel(
                withDeps.communalInteractor,
                Provider { shadeViewController },
                powerManager,
                mediaHost,
            )
    }

    @Test
    fun communalContent_onlyWidgetsAreShownInEditMode() =
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
            mediaRepository.mediaPlaying.value = true

            val communalContent by collectLastValue(underTest.communalContent)

            // Only Widgets are shown.
            assertThat(communalContent?.size).isEqualTo(2)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
        }
}
