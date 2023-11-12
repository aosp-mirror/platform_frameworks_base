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

import android.app.smartspace.SmartspaceTarget
import android.provider.Settings
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalInteractorTest : SysuiTestCase() {
    private lateinit var testScope: TestScope

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = TestScope()

        val withDeps = CommunalInteractorFactory.create()

        tutorialRepository = withDeps.tutorialRepository
        communalRepository = withDeps.communalRepository
        mediaRepository = withDeps.mediaRepository
        widgetRepository = withDeps.widgetRepository
        smartspaceRepository = withDeps.smartspaceRepository
        keyguardRepository = withDeps.keyguardRepository

        underTest = withDeps.communalInteractor
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
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

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
    fun smartspace_onlyShowTimersWithRemoteViews() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Not a timer
            val target1 = mock(SmartspaceTarget::class.java)
            whenever(target1.smartspaceTargetId).thenReturn("target1")
            whenever(target1.featureType).thenReturn(SmartspaceTarget.FEATURE_WEATHER)
            whenever(target1.remoteViews).thenReturn(mock(RemoteViews::class.java))

            // Does not have RemoteViews
            val target2 = mock(SmartspaceTarget::class.java)
            whenever(target1.smartspaceTargetId).thenReturn("target2")
            whenever(target1.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target1.remoteViews).thenReturn(null)

            // Timer and has RemoteViews
            val target3 = mock(SmartspaceTarget::class.java)
            whenever(target1.smartspaceTargetId).thenReturn("target3")
            whenever(target1.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target1.remoteViews).thenReturn(mock(RemoteViews::class.java))

            val targets = listOf(target1, target2, target3)
            smartspaceRepository.setLockscreenSmartspaceTargets(targets)

            val communalContent by collectLastValue(underTest.communalContent)
            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0)?.key).isEqualTo("smartspace_target3")
        }

    @Test
    fun smartspace_smartspaceAndWidgetsAvailable_showSmartspaceAndWidgetContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

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
            val target = mock(SmartspaceTarget::class.java)
            whenever(target.smartspaceTargetId).thenReturn("target")
            whenever(target.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target.remoteViews).thenReturn(mock(RemoteViews::class.java))
            smartspaceRepository.setLockscreenSmartspaceTargets(listOf(target))

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent?.size).isEqualTo(3)
            assertThat(communalContent?.get(0)?.key).isEqualTo("smartspace_target")
            assertThat(communalContent?.get(1)?.key).isEqualTo("widget_0")
            assertThat(communalContent?.get(2)?.key).isEqualTo("widget_1")
        }

    @Test
    fun umo_mediaPlaying_showsUmo() =
        testScope.runTest {
            // Tutorial completed.
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            mediaRepository.mediaPlaying.value = true

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(communalContent?.get(0)?.key).isEqualTo(CommunalContentModel.UMO_KEY)
        }

    @Test
    fun contentOrdering() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

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
            val target = mock(SmartspaceTarget::class.java)
            whenever(target.smartspaceTargetId).thenReturn("target")
            whenever(target.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target.remoteViews).thenReturn(mock(RemoteViews::class.java))
            smartspaceRepository.setLockscreenSmartspaceTargets(listOf(target))

            // Media playing.
            mediaRepository.mediaPlaying.value = true

            val communalContent by collectLastValue(underTest.communalContent)

            // Order is smart space, then UMO, then widget content.
            assertThat(communalContent?.size).isEqualTo(4)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(communalContent?.get(1)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
            assertThat(communalContent?.get(3))
                .isInstanceOf(CommunalContentModel.Widget::class.java)
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
