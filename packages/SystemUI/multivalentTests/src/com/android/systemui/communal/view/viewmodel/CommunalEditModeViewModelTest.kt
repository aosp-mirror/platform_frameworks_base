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

import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.accessibilityManager
import android.widget.RemoteViews
import androidx.activity.result.ActivityResultLauncher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.ui.viewmodel.CommunalEditModeViewModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalEditModeViewModelTest : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>
    @Mock private lateinit var metricsLogger: CommunalMetricsLogger

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeCommunalSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var communalSceneInteractor: CommunalSceneInteractor
    private lateinit var communalInteractor: CommunalInteractor
    private lateinit var accessibilityManager: AccessibilityManager

    private val testableResources = context.orCreateTestableResources

    private lateinit var underTest: CommunalEditModeViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeCommunalSmartspaceRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        communalSceneInteractor = kosmos.communalSceneInteractor
        communalInteractor = spy(kosmos.communalInteractor)
        kosmos.fakeUserRepository.setUserInfos(listOf(MAIN_USER_INFO))
        kosmos.fakeUserTracker.set(
            userInfos = listOf(MAIN_USER_INFO),
            selectedUserIndex = 0,
        )
        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        accessibilityManager = kosmos.accessibilityManager

        underTest =
            CommunalEditModeViewModel(
                communalSceneInteractor,
                communalInteractor,
                kosmos.communalSettingsInteractor,
                kosmos.keyguardTransitionInteractor,
                mediaHost,
                uiEventLogger,
                logcatLogBuffer("CommunalEditModeViewModelTest"),
                kosmos.testDispatcher,
                metricsLogger,
                context,
                accessibilityManager,
                packageManager,
                WIDGET_PICKER_PACKAGE_NAME,
            )
    }

    @Test
    fun communalContent_onlyWidgetsAndCtaTileAreShownInEditMode() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Smartspace available.
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    )
                )
            )

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
    fun isCommunalContentVisible_isTrue_whenEditModeShowing() =
        testScope.runTest {
            val isCommunalContentVisible by collectLastValue(underTest.isCommunalContentVisible)
            communalSceneInteractor.setEditModeState(EditModeState.SHOWING)
            assertThat(isCommunalContentVisible).isEqualTo(true)
        }

    @Test
    fun isCommunalContentVisible_isFalse_whenEditModeNotShowing() =
        testScope.runTest {
            val isCommunalContentVisible by collectLastValue(underTest.isCommunalContentVisible)
            communalSceneInteractor.setEditModeState(null)
            assertThat(isCommunalContentVisible).isEqualTo(false)
        }

    @Test
    fun deleteWidget() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            val communalContent by collectLastValue(underTest.communalContent)

            // Widgets and CTA tile are shown.
            assertThat(communalContent?.size).isEqualTo(2)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)

            underTest.onDeleteWidget(
                id = 0,
                componentName = ComponentName("test_package", "test_class"),
                rank = 30,
            )

            // Only one widget and CTA tile remain.
            assertThat(communalContent?.size).isEqualTo(1)
            val item = communalContent?.get(0)
            val appWidgetId =
                if (item is CommunalContentModel.WidgetContent) item.appWidgetId else null
            assertThat(appWidgetId).isEqualTo(1)
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
            val success =
                underTest.onOpenWidgetPicker(testableResources.resources, activityResultLauncher)

            verify(activityResultLauncher).launch(any())
            assertTrue(success)
        }
    }

    @Test
    fun onOpenWidgetPicker_activityLaunchThrowsException_failure() {
        testScope.runTest {
            whenever(activityResultLauncher.launch(any()))
                .thenThrow(ActivityNotFoundException::class.java)

            val success =
                underTest.onOpenWidgetPicker(
                    testableResources.resources,
                    activityResultLauncher,
                )

            assertFalse(success)
        }
    }

    @Test
    fun showDisclaimer_trueAfterEditModeShowing() =
        testScope.runTest {
            val showDisclaimer by collectLastValue(underTest.showDisclaimer)

            assertThat(showDisclaimer).isFalse()
            underTest.setEditModeState(EditModeState.SHOWING)
            assertThat(showDisclaimer).isTrue()
        }

    @Test
    fun showDisclaimer_falseWhenDismissed() =
        testScope.runTest {
            underTest.setEditModeState(EditModeState.SHOWING)
            kosmos.fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)

            val showDisclaimer by collectLastValue(underTest.showDisclaimer)

            assertThat(showDisclaimer).isTrue()
            underTest.onDisclaimerDismissed()
            assertThat(showDisclaimer).isFalse()
        }

    @Test
    fun showDisclaimer_trueWhenTimeout() =
        testScope.runTest {
            underTest.setEditModeState(EditModeState.SHOWING)
            kosmos.fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)

            val showDisclaimer by collectLastValue(underTest.showDisclaimer)

            assertThat(showDisclaimer).isTrue()
            underTest.onDisclaimerDismissed()
            assertThat(showDisclaimer).isFalse()
            advanceTimeBy(CommunalInteractor.DISCLAIMER_RESET_MILLIS)
            assertThat(showDisclaimer).isTrue()
        }

    @Test
    fun scrollPosition_persistedOnEditCleanup() {
        val index = 2
        val offset = 30
        underTest.onScrollPositionUpdated(index, offset)
        underTest.cleanupEditModeState()

        verify(communalInteractor).setScrollPosition(eq(index), eq(offset))
    }

    @Test
    fun onNewWidgetAdded_accessibilityDisabled_doNothing() {
        whenever(accessibilityManager.isEnabled).thenReturn(false)

        val provider =
            mock<AppWidgetProviderInfo> {
                on { loadLabel(packageManager) }.thenReturn("Test Clock")
            }
        underTest.onNewWidgetAdded(provider)

        verify(accessibilityManager, never()).sendAccessibilityEvent(any())
    }

    @Test
    fun onNewWidgetAdded_accessibilityEnabled_sendAccessibilityAnnouncement() {
        whenever(accessibilityManager.isEnabled).thenReturn(true)

        val provider =
            mock<AppWidgetProviderInfo> {
                on { loadLabel(packageManager) }.thenReturn("Test Clock")
            }
        underTest.onNewWidgetAdded(provider)

        val captor = argumentCaptor<AccessibilityEvent>()
        verify(accessibilityManager).sendAccessibilityEvent(captor.capture())

        val event = captor.firstValue
        assertThat(event.eventType).isEqualTo(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        assertThat(event.contentDescription).isEqualTo("Test Clock widget added to lock screen")
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        const val WIDGET_PICKER_PACKAGE_NAME = "widget_picker_package_name"
    }
}
