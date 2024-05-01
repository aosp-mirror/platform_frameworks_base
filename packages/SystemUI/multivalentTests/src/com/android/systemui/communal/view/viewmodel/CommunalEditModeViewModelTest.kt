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
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserInfo
import android.os.UserHandle
import android.provider.Settings
import android.widget.RemoteViews
import androidx.activity.result.ActivityResultLauncher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.smartspace.data.repository.fakeSmartspaceRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalEditModeViewModelTest : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var providerInfo: AppWidgetProviderInfo
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository

    private val testableResources = context.orCreateTestableResources

    private lateinit var underTest: CommunalEditModeViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeSmartspaceRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        kosmos.fakeUserTracker.set(
            userInfos = listOf(MAIN_USER_INFO),
            selectedUserIndex = 0,
        )
        whenever(providerInfo.profile).thenReturn(UserHandle(MAIN_USER_INFO.id))

        underTest =
            CommunalEditModeViewModel(
                kosmos.communalInteractor,
                kosmos.communalSettingsInteractor,
                mediaHost,
                uiEventLogger,
                logcatLogBuffer("CommunalEditModeViewModelTest"),
                kosmos.testDispatcher,
            )
    }

    @Test
    fun communalContent_onlyWidgetsAndCtaTileAreShownInEditMode() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            val widgets =
                listOf(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 0,
                        priority = 30,
                        providerInfo = providerInfo,
                    ),
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        priority = 20,
                        providerInfo = providerInfo,
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
            assertThat(communalContent?.size).isEqualTo(2)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
        }

    @Test
    fun selectedKey_onReorderWidgets_isCleared() =
        testScope.runTest {
            val selectedKey by collectLastValue(underTest.selectedKey)

            val key = CommunalContentModel.KEY.widget(123)
            underTest.setSelectedKey(key)
            assertThat(selectedKey).isEqualTo(key)

            underTest.onReorderWidgetStart()
            assertThat(selectedKey).isNull()
        }

    @Test
    fun deleteWidget() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            val widgets =
                listOf(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 0,
                        priority = 30,
                        providerInfo = providerInfo,
                    ),
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        priority = 20,
                        providerInfo = providerInfo,
                    ),
                )
            widgetRepository.setCommunalWidgets(widgets)

            val communalContent by collectLastValue(underTest.communalContent)

            // Widgets and CTA tile are shown.
            assertThat(communalContent?.size).isEqualTo(2)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)

            underTest.onDeleteWidget(widgets.get(0).appWidgetId)

            // Only one widget and CTA tile remain.
            assertThat(communalContent?.size).isEqualTo(1)
            val item = communalContent?.get(0)
            val appWidgetId =
                if (item is CommunalContentModel.WidgetContent) item.appWidgetId else null
            assertThat(appWidgetId).isEqualTo(widgets.get(1).appWidgetId)
        }

    @Test
    fun reorderWidget_uiEventLogging_start() {
        underTest.onReorderWidgetStart()
        verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_START)
    }

    @Test
    fun reorderWidget_uiEventLogging_end() {
        underTest.onReorderWidgetEnd()
        verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_FINISH)
    }

    @Test
    fun reorderWidget_uiEventLogging_cancel() {
        underTest.onReorderWidgetCancel()
        verify(uiEventLogger).log(CommunalUiEvent.COMMUNAL_HUB_REORDER_WIDGET_CANCEL)
    }

    @Test
    fun onOpenWidgetPicker_launchesWidgetPickerActivity() {
        testScope.runTest {
            whenever(packageManager.resolveActivity(any(), anyInt())).then {
                ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply { packageName = WIDGET_PICKER_PACKAGE_NAME }
                }
            }

            val success =
                underTest.onOpenWidgetPicker(
                    testableResources.resources,
                    packageManager,
                    activityResultLauncher
                )

            verify(activityResultLauncher).launch(any())
            assertTrue(success)
        }
    }

    @Test
    fun onOpenWidgetPicker_launcherActivityNotResolved_doesNotLaunchWidgetPickerActivity() {
        testScope.runTest {
            whenever(packageManager.resolveActivity(any(), anyInt())).thenReturn(null)

            val success =
                underTest.onOpenWidgetPicker(
                    testableResources.resources,
                    packageManager,
                    activityResultLauncher
                )

            verify(activityResultLauncher, never()).launch(any())
            assertFalse(success)
        }
    }

    @Test
    fun onOpenWidgetPicker_activityLaunchThrowsException_failure() {
        testScope.runTest {
            whenever(packageManager.resolveActivity(any(), anyInt())).then {
                ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply { packageName = WIDGET_PICKER_PACKAGE_NAME }
                }
            }

            whenever(activityResultLauncher.launch(any()))
                .thenThrow(ActivityNotFoundException::class.java)

            val success =
                underTest.onOpenWidgetPicker(
                    testableResources.resources,
                    packageManager,
                    activityResultLauncher,
                )

            assertFalse(success)
        }
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        const val WIDGET_PICKER_PACKAGE_NAME = "widget_picker_package_name"
    }
}
