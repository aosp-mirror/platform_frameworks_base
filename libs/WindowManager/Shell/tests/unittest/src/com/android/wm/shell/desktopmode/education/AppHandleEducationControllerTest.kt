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

package com.android.wm.shell.desktopmode.education

import android.os.SystemProperties
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import androidx.test.filters.SmallTest
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.CaptionState
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository
import com.android.wm.shell.desktopmode.education.AppHandleEducationController.Companion.APP_HANDLE_EDUCATION_DELAY_MILLIS
import com.android.wm.shell.desktopmode.education.AppHandleEducationController.Companion.APP_HANDLE_EDUCATION_TIMEOUT_MILLIS
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.util.createAppHandleState
import com.android.wm.shell.util.createAppHeaderState
import com.android.wm.shell.util.createWindowingEducationProto
import com.android.wm.shell.windowdecor.education.DesktopWindowingEducationTooltipController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests of [AppHandleEducationController] Usage: atest AppHandleEducationControllerTest */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppHandleEducationControllerTest : ShellTestCase() {
  @JvmField
  @Rule
  val extendedMockitoRule =
      ExtendedMockitoRule.Builder(this)
          .mockStatic(DesktopModeStatus::class.java)
          .mockStatic(SystemProperties::class.java)
          .build()!!
  @JvmField @Rule val setFlagsRule = SetFlagsRule()

  private lateinit var educationController: AppHandleEducationController
  private lateinit var testableContext: TestableContext
  private val testScope = TestScope()
  private val testDataStoreFlow = MutableStateFlow(createWindowingEducationProto())
  private val testCaptionStateFlow = MutableStateFlow<CaptionState>(CaptionState.NoCaption)
  private val educationConfigCaptor =
      argumentCaptor<DesktopWindowingEducationTooltipController.TooltipEducationViewConfig>()
  @Mock private lateinit var mockEducationFilter: AppHandleEducationFilter
  @Mock private lateinit var mockDataStoreRepository: AppHandleEducationDatastoreRepository
  @Mock private lateinit var mockCaptionHandleRepository: WindowDecorCaptionHandleRepository
  @Mock private lateinit var mockTooltipController: DesktopWindowingEducationTooltipController

  @Before
  fun setUp() {
    MockitoAnnotations.initMocks(this)
    Dispatchers.setMain(StandardTestDispatcher(testScope.testScheduler))
    testableContext = TestableContext(mContext)
    whenever(mockDataStoreRepository.dataStoreFlow).thenReturn(testDataStoreFlow)
    whenever(mockCaptionHandleRepository.captionStateFlow).thenReturn(testCaptionStateFlow)
    whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)

    educationController =
        AppHandleEducationController(
            testableContext,
            mockEducationFilter,
            mockDataStoreRepository,
            mockCaptionHandleRepository,
            mockTooltipController,
            testScope.backgroundScope,
            Dispatchers.Main)
  }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_appHandleVisible_shouldCallShowEducationTooltip() =
      testScope.runTest {
        // App handle is visible. Should show education tooltip.
        setShouldShowAppHandleEducation(true)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState()
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(1)).showEducationTooltip(any(), any())
      }

  @Test
  @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_flagDisabled_shouldNotCallShowEducationTooltip() =
      testScope.runTest {
        // App handle visible but education aconfig flag disabled, should not show education
        // tooltip.
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(false)
        setShouldShowAppHandleEducation(true)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState()
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, never()).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_shouldShowAppHandleEducationReturnsFalse_shouldNotCallShowEducationTooltip() =
      testScope.runTest {
        // App handle is visible but [shouldShowAppHandleEducation] api returns false, should not
        // show education tooltip.
        setShouldShowAppHandleEducation(false)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState()
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, never()).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_appHandleNotVisible_shouldNotCallShowEducationTooltip() =
      testScope.runTest {
        // App handle is not visible, should not show education tooltip.
        setShouldShowAppHandleEducation(true)

        // Simulate app handle is not visible.
        testCaptionStateFlow.value = CaptionState.NoCaption
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, never()).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_appHandleHintViewedAlready_shouldNotCallShowEducationTooltip() =
      testScope.runTest {
        // App handle is visible but app handle hint has been viewed before,
        // should not show education tooltip.
        // Mark app handle hint viewed.
        testDataStoreFlow.value =
            createWindowingEducationProto(appHandleHintViewedTimestampMillis = 123L)
        setShouldShowAppHandleEducation(true)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = false)
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, never()).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun overridePrerequisite_appHandleHintViewedAlready_shouldCallShowEducationTooltip() =
      testScope.runTest {
        // App handle is visible but app handle hint has been viewed before.
        // But as we are overriding prerequisite conditions, we should show app
        // handle tooltip.
        // Mark app handle hint viewed.
        testDataStoreFlow.value =
            createWindowingEducationProto(appHandleHintViewedTimestampMillis = 123L)
        val systemPropertiesKey =
            "persist.desktop_windowing_app_handle_education_override_conditions"
        whenever(SystemProperties.getBoolean(eq(systemPropertiesKey), anyBoolean()))
            .thenReturn(true)
        setShouldShowAppHandleEducation(true)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = false)
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(1)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_appHandleExpanded_shouldMarkAppHandleHintUsed() =
      testScope.runTest {
        setShouldShowAppHandleEducation(false)

        // Simulate app handle visible and expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        // Wait for some time before verifying
        waitForBufferDelay()

        verify(mockDataStoreRepository, times(1)).updateAppHandleHintUsedTimestampMillis(eq(true))
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun init_showFirstTooltip_shouldMarkAppHandleHintViewed() =
      testScope.runTest {
        // App handle is visible. Should show education tooltip.
        setShouldShowAppHandleEducation(true)

        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState()
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockDataStoreRepository, times(1)).updateAppHandleHintViewedTimestampMillis(eq(true))
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showWindowingImageButtonTooltip_appHandleExpanded_shouldCallShowEducationTooltipTwice() =
      testScope.runTest {
        // After first tooltip is dismissed, app handle is expanded. Should show second education
        // tooltip.
        showAndDismissFirstTooltip()

        // Simulate app handle expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        // [showEducationTooltip] should be called twice, once for each tooltip.
        verify(mockTooltipController, times(2)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showWindowingImageButtonTooltip_appHandleExpandedAfterTimeout_shouldCallShowEducationTooltipOnce() =
      testScope.runTest {
        // After first tooltip is dismissed, app handle is expanded after timeout. Should not show
        // second education tooltip.
        showAndDismissFirstTooltip()

        // Wait for timeout to occur, after this timeout we should not listen for further triggers
        // anymore.
        advanceTimeBy(APP_HANDLE_EDUCATION_TIMEOUT_BUFFER_MILLIS)
        runCurrent()
        // Simulate app handle expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        // [showEducationTooltip] should be called once, just for the first tooltip.
        verify(mockTooltipController, times(1)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showWindowingImageButtonTooltip_appHandleExpandedTwice_shouldCallShowEducationTooltipTwice() =
      testScope.runTest {
        // After first tooltip is dismissed, app handle is expanded twice. Should show second
        // education tooltip only once.
        showAndDismissFirstTooltip()

        // Simulate app handle expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        // Wait for next tooltip to showup.
        waitForBufferDelay()
        // Simulate app handle being expanded twice.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        waitForBufferDelay()

        // [showEducationTooltip] should not be called thrice, even if app handle was expanded
        // twice. Should be called twice, once for each tooltip.
        verify(mockTooltipController, times(2)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showWindowingImageButtonTooltip_appHandleNotExpanded_shouldCallShowEducationTooltipOnce() =
      testScope.runTest {
        // After first tooltip is dismissed, app handle is not expanded. Should not show second
        // education tooltip.
        showAndDismissFirstTooltip()

        // Simulate app handle visible but not expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = false)
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        // [showEducationTooltip] should be called once, just for the first tooltip.
        verify(mockTooltipController, times(1)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showExitWindowingButtonTooltip_appHeaderVisible_shouldCallShowEducationTooltipThrice() =
      testScope.runTest {
        // After first two tooltips are dismissed, app header is visible. Should show third
        // education tooltip.
        showAndDismissFirstTooltip()
        showAndDismissSecondTooltip()

        // Simulate app header visible.
        testCaptionStateFlow.value = createAppHeaderState()
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(3)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showExitWindowingButtonTooltip_appHeaderVisibleAfterTimeout_shouldCallShowEducationTooltipTwice() =
      testScope.runTest {
        // After first two tooltips are dismissed, app header is visible after timeout. Should not
        // show third education tooltip.
        showAndDismissFirstTooltip()
        showAndDismissSecondTooltip()

        // Wait for timeout to occur, after this timeout we should not listen for further triggers
        // anymore.
        advanceTimeBy(APP_HANDLE_EDUCATION_TIMEOUT_BUFFER_MILLIS)
        runCurrent()
        // Simulate app header visible.
        testCaptionStateFlow.value = createAppHeaderState()
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(2)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showExitWindowingButtonTooltip_appHeaderVisibleTwice_shouldCallShowEducationTooltipThrice() =
      testScope.runTest {
        // After first two tooltips are dismissed, app header is visible twice. Should show third
        // education tooltip only once.
        showAndDismissFirstTooltip()
        showAndDismissSecondTooltip()

        // Simulate app header visible.
        testCaptionStateFlow.value = createAppHeaderState()
        // Wait for next tooltip to showup.
        waitForBufferDelay()
        testCaptionStateFlow.value = createAppHeaderState()
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(3)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun showExitWindowingButtonTooltip_appHeaderExpanded_shouldCallShowEducationTooltipTwice() =
      testScope.runTest {
        // After first two tooltips are dismissed, app header is visible but expanded. Should not
        // show third education tooltip.
        showAndDismissFirstTooltip()
        showAndDismissSecondTooltip()

        // Simulate app header visible.
        testCaptionStateFlow.value = createAppHeaderState(isHeaderMenuExpanded = true)
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, times(2)).showEducationTooltip(any(), any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  fun setAppHandleEducationTooltipCallbacks_onAppHandleTooltipClicked_callbackInvoked() =
      testScope.runTest {
        // App handle is visible. Should show education tooltip.
        setShouldShowAppHandleEducation(true)
        val mockOpenHandleMenuCallback: (Int) -> Unit = mock()
        val mockToDesktopModeCallback: (Int, DesktopModeTransitionSource) -> Unit = mock()
        educationController.setAppHandleEducationTooltipCallbacks(
            mockOpenHandleMenuCallback, mockToDesktopModeCallback)
        // Simulate app handle visible.
        testCaptionStateFlow.value = createAppHandleState()
        // Wait for first tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, atLeastOnce())
            .showEducationTooltip(educationConfigCaptor.capture(), any())
        educationConfigCaptor.lastValue.onEducationClickAction.invoke()

        verify(mockOpenHandleMenuCallback, times(1)).invoke(any())
      }

  @Test
  @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_APP_HANDLE_EDUCATION)
  @Ignore("b/371527084: revisit testcase after refactoring original logic")
  fun setAppHandleEducationTooltipCallbacks_onWindowingImageButtonTooltipClicked_callbackInvoked() =
      testScope.runTest {
        // After first tooltip is dismissed, app handle is expanded. Should show second education
        // tooltip.
        showAndDismissFirstTooltip()
        val mockOpenHandleMenuCallback: (Int) -> Unit = mock()
        val mockToDesktopModeCallback: (Int, DesktopModeTransitionSource) -> Unit = mock()
        educationController.setAppHandleEducationTooltipCallbacks(
            mockOpenHandleMenuCallback, mockToDesktopModeCallback)
        // Simulate app handle expanded.
        testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
        // Wait for next tooltip to showup.
        waitForBufferDelay()

        verify(mockTooltipController, atLeastOnce())
            .showEducationTooltip(educationConfigCaptor.capture(), any())
        educationConfigCaptor.lastValue.onEducationClickAction.invoke()

        verify(mockToDesktopModeCallback, times(1)).invoke(any(), any())
      }

  private suspend fun TestScope.showAndDismissFirstTooltip() {
    setShouldShowAppHandleEducation(true)
    // Simulate app handle visible.
    testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = false)
    // Wait for first tooltip to showup.
    waitForBufferDelay()
    // [shouldShowAppHandleEducation] should return false as education has been viewed
    // before.
    setShouldShowAppHandleEducation(false)
    // Dismiss previous tooltip, after this we should listen for next tooltip's trigger.
    captureAndInvokeOnDismissAction()
  }

  private fun TestScope.showAndDismissSecondTooltip() {
    // Simulate app handle expanded.
    testCaptionStateFlow.value = createAppHandleState(isHandleMenuExpanded = true)
    // Wait for next tooltip to showup.
    waitForBufferDelay()
    // Dismiss previous tooltip, after this we should listen for next tooltip's trigger.
    captureAndInvokeOnDismissAction()
  }

  private fun captureAndInvokeOnDismissAction() {
    verify(mockTooltipController, atLeastOnce())
        .showEducationTooltip(educationConfigCaptor.capture(), any())
    educationConfigCaptor.lastValue.onDismissAction.invoke()
  }

  private suspend fun setShouldShowAppHandleEducation(shouldShowAppHandleEducation: Boolean) =
      whenever(mockEducationFilter.shouldShowAppHandleEducation(any()))
          .thenReturn(shouldShowAppHandleEducation)

  /**
   * Class under test waits for some time before showing education, simulate advance time before
   * verifying or moving forward
   */
  private fun TestScope.waitForBufferDelay() {
    advanceTimeBy(APP_HANDLE_EDUCATION_DELAY_BUFFER_MILLIS)
    runCurrent()
  }

  private companion object {
    val APP_HANDLE_EDUCATION_DELAY_BUFFER_MILLIS: Long = APP_HANDLE_EDUCATION_DELAY_MILLIS + 1000L
    val APP_HANDLE_EDUCATION_TIMEOUT_BUFFER_MILLIS: Long =
        APP_HANDLE_EDUCATION_TIMEOUT_MILLIS + 1000L
  }
}
