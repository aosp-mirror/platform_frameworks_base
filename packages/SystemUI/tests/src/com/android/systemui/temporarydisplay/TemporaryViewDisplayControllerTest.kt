/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.temporarydisplay

import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.wakelock.WakeLock
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class TemporaryViewDisplayControllerTest : SysuiTestCase() {
    private lateinit var underTest: TestController

    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor

    private lateinit var fakeWakeLockBuilder: WakeLockFake.Builder
    private lateinit var fakeWakeLock: WakeLockFake

    @Mock
    private lateinit var logger: TemporaryViewLogger<ViewInfo>
    @Mock
    private lateinit var accessibilityManager: AccessibilityManager
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any()))
            .thenAnswer { it.arguments[0] }

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        fakeWakeLock = WakeLockFake()
        fakeWakeLockBuilder = WakeLockFake.Builder(context)
        fakeWakeLockBuilder.setWakeLock(fakeWakeLock)

        underTest = TestController(
            context,
            logger,
            windowManager,
            fakeExecutor,
            accessibilityManager,
            configurationController,
            dumpManager,
            powerManager,
            fakeWakeLockBuilder,
            fakeClock,
        )
        underTest.start()
    }

    @Test
    fun displayView_viewAddedWithCorrectTitle() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "Fake Window Title",
            )
        )

        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(any(), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value!!.title).isEqualTo("Fake Window Title")
    }

    @Test
    fun displayView_logged() {
        val info = ViewInfo(
            name = "name",
            windowTitle = "Fake Window Title",
        )

        underTest.displayView(info)

        verify(logger).logViewAddition(info)
    }

    @Test
    fun displayView_wakeLockAcquired() {
        underTest.displayView(getState())

        assertThat(fakeWakeLock.isHeld).isTrue()
    }

    @Test
    fun displayView_screenAlreadyOn_wakeLockAcquired() {
        whenever(powerManager.isScreenOn).thenReturn(true)

        underTest.displayView(getState())

        assertThat(fakeWakeLock.isHeld).isTrue()
    }

    @Test
    fun displayView_wakeLockCanBeReleasedAfterTimeOut() {
        underTest.displayView(getState())
        assertThat(fakeWakeLock.isHeld).isTrue()

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun displayView_removeView_wakeLockCanBeReleased() {
        underTest.displayView(getState())
        assertThat(fakeWakeLock.isHeld).isTrue()

        underTest.removeView("id", "test reason")

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun displayView_twice_viewNotAddedTwice() {
        underTest.displayView(getState())
        reset(windowManager)

        underTest.displayView(getState())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun displayView_twiceWithDifferentIds_oldViewRemovedNewViewAdded() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                id = "First",
                windowTitle = "First Fake Window Title",
            )
        )

        underTest.displayView(
            ViewInfo(
                name = "name",
                id = "Second",
                windowTitle = "Second Fake Window Title",
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()

        verify(windowManager, times(2)).addView(capture(viewCaptor), capture(windowParamsCaptor))

        assertThat(windowParamsCaptor.allValues[0].title).isEqualTo("First Fake Window Title")
        assertThat(windowParamsCaptor.allValues[1].title).isEqualTo("Second Fake Window Title")
        verify(windowManager).removeView(viewCaptor.allValues[0])
    }

    @Test
    fun displayView_viewDoesNotDisappearsBeforeTimeout() {
        val state = getState()
        underTest.displayView(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS - 1)

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayView_viewDisappearsAfterTimeout() {
        val state = getState()
        underTest.displayView(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun displayView_calledAgainBeforeTimeout_timeoutReset() {
        // First, display the view
        val state = getState()
        underTest.displayView(state)

        // After some time, re-display the view
        val waitTime = 1000L
        fakeClock.advanceTime(waitTime)
        underTest.displayView(getState())

        // Wait until the timeout for the first display would've happened
        fakeClock.advanceTime(TIMEOUT_MS - waitTime + 1)

        // Verify we didn't hide the view
        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayView_calledAgainBeforeTimeout_eventuallyTimesOut() {
        // First, display the view
        val state = getState()
        underTest.displayView(state)

        // After some time, re-display the view
        fakeClock.advanceTime(1000L)
        underTest.displayView(getState())

        // Ensure we still hide the view eventually
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun displayScaleChange_viewReinflatedWithMostRecentState() {
        underTest.displayView(getState(name = "First name"))
        underTest.displayView(getState(name = "Second name"))
        reset(windowManager)

        getConfigurationListener().onDensityOrFontScaleChanged()

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("Second name")
    }

    @Test
    fun viewUpdatedWithNewOnViewTimeoutRunnable_newRunnableUsed() {
        var runnable1Run = false
        underTest.displayView(ViewInfo(name = "name", id = "id1", windowTitle = "1")) {
            runnable1Run = true
        }

        var runnable2Run = false
        underTest.displayView(ViewInfo(name = "name", id = "id1", windowTitle = "1")) {
            runnable2Run = true
        }

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        assertThat(runnable1Run).isFalse()
        assertThat(runnable2Run).isTrue()
    }

    @Test
    fun multipleViewsWithDifferentIds_moreRecentReplacesOlder() {
        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "First Fake Window Title",
                id = "id1"
            )
        )

        underTest.displayView(
            ViewInfo(
                name = "name",
                windowTitle = "Second Fake Window Title",
                id = "id2"
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()

        verify(windowManager, times(2)).addView(capture(viewCaptor), capture(windowParamsCaptor))

        assertThat(windowParamsCaptor.allValues[0].title).isEqualTo("First Fake Window Title")
        assertThat(windowParamsCaptor.allValues[1].title).isEqualTo("Second Fake Window Title")
        verify(windowManager).removeView(viewCaptor.allValues[0])
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun multipleViewsWithDifferentIds_recentActiveViewIsDisplayed() {
        underTest.displayView(ViewInfo("First name", id = "id1"))

        verify(windowManager).addView(any(), any())
        reset(windowManager)

        underTest.displayView(ViewInfo("Second name", id = "id2"))

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        underTest.removeView("id2", "test reason")

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        assertThat(underTest.mostRecentViewInfo?.id).isEqualTo("id1")
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("First name")

        reset(windowManager)
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun multipleViewsWithDifferentIds_oldViewRemoved_recentViewIsDisplayed() {
        underTest.displayView(ViewInfo("First name", id = "id1"))

        verify(windowManager).addView(any(), any())
        reset(windowManager)

        underTest.displayView(ViewInfo("Second name", id = "id2"))

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        // WHEN an old view is removed
        underTest.removeView("id1", "test reason")

        // THEN we don't update anything
        verify(windowManager, never()).removeView(any())
        assertThat(underTest.mostRecentViewInfo?.id).isEqualTo("id2")
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("Second name")
        verify(configurationController, never()).removeCallback(any())

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun multipleViewsWithDifferentIds_threeDifferentViews_recentActiveViewIsDisplayed() {
        underTest.displayView(ViewInfo("First name", id = "id1"))
        underTest.displayView(ViewInfo("Second name", id = "id2"))
        underTest.displayView(ViewInfo("Third name", id = "id3"))

        verify(windowManager, times(3)).addView(any(), any())
        verify(windowManager, times(2)).removeView(any())

        reset(windowManager)
        underTest.removeView("id3", "test reason")

        verify(windowManager).removeView(any())
        assertThat(underTest.mostRecentViewInfo?.id).isEqualTo("id2")
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("Second name")
        verify(configurationController, never()).removeCallback(any())

        reset(windowManager)
        underTest.removeView("id2", "test reason")

        verify(windowManager).removeView(any())
        assertThat(underTest.mostRecentViewInfo?.id).isEqualTo("id1")
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("First name")
        verify(configurationController, never()).removeCallback(any())

        reset(windowManager)
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun multipleViewsWithDifferentIds_oneViewStateChanged_stackHasRecentState() {
        underTest.displayView(ViewInfo("First name", id = "id1"))
        underTest.displayView(ViewInfo("New name", id = "id1"))

        verify(windowManager).addView(any(), any())
        reset(windowManager)

        underTest.displayView(ViewInfo("Second name", id = "id2"))

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        underTest.removeView("id2", "test reason")

        verify(windowManager).removeView(any())
        verify(windowManager).addView(any(), any())
        assertThat(underTest.mostRecentViewInfo?.id).isEqualTo("id1")
        assertThat(underTest.mostRecentViewInfo?.name).isEqualTo("New name")
        assertThat(underTest.activeViews[0].info.name).isEqualTo("New name")

        reset(windowManager)
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
    }

    @Test
    fun multipleViews_mostRecentViewRemoved_otherViewsTimedOutAndNotDisplayed() {
        underTest.displayView(ViewInfo("First name", id = "id1", timeoutMs = 4000))
        fakeClock.advanceTime(1000)
        underTest.displayView(ViewInfo("Second name", id = "id2", timeoutMs = 4000))
        fakeClock.advanceTime(1000)
        underTest.displayView(ViewInfo("Third name", id = "id3", timeoutMs = 20000))

        reset(windowManager)
        fakeClock.advanceTime(20000 + 1)

        verify(windowManager).removeView(any())
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun multipleViews_mostRecentViewRemoved_viewWithShortTimeLeftNotDisplayed() {
        underTest.displayView(ViewInfo("First name", id = "id1", timeoutMs = 4000))
        fakeClock.advanceTime(1000)
        underTest.displayView(ViewInfo("Second name", id = "id2", timeoutMs = 2500))

        reset(windowManager)
        fakeClock.advanceTime(2500 + 1)
        // At this point, 3501ms have passed, so id1 only has 499ms left which is not enough.
        // So, it shouldn't be displayed.

        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun lowerThenHigherPriority_higherReplacesLower() {
        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
            )
        )

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title")
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun lowerThenHigherPriority_lowerPriorityRedisplayed() {
        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
                timeoutMs = 10000
            )
        )

        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
                timeoutMs = 2000
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager, times(2)).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.allValues[0].title).isEqualTo("Normal Window Title")
        assertThat(windowParamsCaptor.allValues[1].title).isEqualTo("Critical Window Title")
        verify(windowManager).removeView(viewCaptor.allValues[0])

        reset(windowManager)

        // WHEN the critical's timeout has expired
        fakeClock.advanceTime(2000 + 1)

        // THEN the normal view is re-displayed
        verify(windowManager).removeView(viewCaptor.allValues[1])
        verify(windowManager).addView(any(), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title")
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun lowerThenHigherPriority_lowerPriorityNotRedisplayedBecauseTimedOut() {
        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
                timeoutMs = 1000
            )
        )

        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
                timeoutMs = 2000
            )
        )
        reset(windowManager)

        // WHEN the critical's timeout has expired
        fakeClock.advanceTime(2000 + 1)

        // THEN the normal view is not re-displayed since it already timed out
        verify(windowManager).removeView(any())
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews).isEmpty()
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun higherThenLowerPriority_higherStaysDisplayed() {
        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
            )
        )

        verify(windowManager, never()).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(2)
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun higherThenLowerPriority_lowerEventuallyDisplayed() {
        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
                timeoutMs = 3000,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
                timeoutMs = 5000,
            )
        )

        verify(windowManager, never()).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(2)

        // WHEN the first critical view has timed out
        fakeClock.advanceTime(3000 + 1)

        // THEN the second normal view is displayed
        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title")
        assertThat(underTest.activeViews.size).isEqualTo(1)
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun higherThenLowerPriority_lowerNotDisplayedBecauseTimedOut() {
        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
                timeoutMs = 3000,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
                timeoutMs = 200,
            )
        )

        verify(windowManager, never()).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // WHEN the first critical view has timed out
        fakeClock.advanceTime(3000 + 1)

        // THEN the second normal view is not displayed because it's already timed out
        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews).isEmpty()
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun criticalThenNewCritical_newCriticalDisplayed() {
        underTest.displayView(
            ViewInfo(
                name = "critical 1",
                windowTitle = "Critical Window Title 1",
                id = "critical1",
                priority = ViewPriority.CRITICAL,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title 1")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "critical 2",
                windowTitle = "Critical Window Title 2",
                id = "critical2",
                priority = ViewPriority.CRITICAL,
            )
        )

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title 2")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun normalThenNewNormal_newNormalDisplayed() {
        underTest.displayView(
            ViewInfo(
                name = "normal 1",
                windowTitle = "Normal Window Title 1",
                id = "normal1",
                priority = ViewPriority.NORMAL,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title 1")
        reset(windowManager)

        underTest.displayView(
            ViewInfo(
                name = "normal 2",
                windowTitle = "Normal Window Title 2",
                id = "normal2",
                priority = ViewPriority.NORMAL,
            )
        )

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title 2")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        verify(configurationController, never()).removeCallback(any())
    }

    @Test
    fun lowerPriorityViewUpdatedWhileHigherPriorityDisplayed_eventuallyDisplaysUpdated() {
        // First, display a lower priority view
        underTest.displayView(
            ViewInfo(
                name = "normal",
                windowTitle = "Normal Window Title",
                id = "normal",
                priority = ViewPriority.NORMAL,
                // At the end of the test, we'll verify that this information isn't re-displayed.
                // Use a super long timeout so that, when we verify it wasn't re-displayed, we know
                // that it wasn't because the view just timed out.
                timeoutMs = 100000,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title")
        reset(windowManager)

        // Then, display a higher priority view
        fakeClock.advanceTime(1000)
        underTest.displayView(
            ViewInfo(
                name = "critical",
                windowTitle = "Critical Window Title",
                id = "critical",
                priority = ViewPriority.CRITICAL,
                timeoutMs = 3000,
            )
        )

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Critical Window Title")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // While the higher priority view is displayed, update the lower priority view with new
        // information
        fakeClock.advanceTime(1000)
        val updatedViewInfo = ViewInfo(
            name = "normal with update",
            windowTitle = "Normal Window Title",
            id = "normal",
            priority = ViewPriority.NORMAL,
            timeoutMs = 4000,
        )
        underTest.displayView(updatedViewInfo)

        verify(windowManager, never()).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // WHEN the higher priority view times out
        fakeClock.advanceTime(2001)

        // THEN the higher priority view disappears and the lower priority view *with the updated
        // information* gets displayed.
        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Normal Window Title")
        assertThat(underTest.activeViews.size).isEqualTo(1)
        assertThat(underTest.mostRecentViewInfo).isEqualTo(updatedViewInfo)
        reset(windowManager)

        // WHEN the updated view times out
        fakeClock.advanceTime(2001)

        // THEN the old information is never displayed
        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
    }

    @Test
    fun oldViewUpdatedWhileNewViewDisplayed_eventuallyDisplaysUpdated() {
        // First, display id1 view
        underTest.displayView(
            ViewInfo(
                name = "name 1",
                windowTitle = "Name 1 Title",
                id = "id1",
                priority = ViewPriority.NORMAL,
                // At the end of the test, we'll verify that this information isn't re-displayed.
                // Use a super long timeout so that, when we verify it wasn't re-displayed, we know
                // that it wasn't because the view just timed out.
                timeoutMs = 100000,
            )
        )

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Name 1 Title")
        reset(windowManager)

        // Then, display a new id2 view
        fakeClock.advanceTime(1000)
        underTest.displayView(
            ViewInfo(
                name = "name 2",
                windowTitle = "Name 2 Title",
                id = "id2",
                priority = ViewPriority.NORMAL,
                timeoutMs = 3000,
            )
        )

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Name 2 Title")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // While the id2 view is displayed, re-display the id1 view with new information
        fakeClock.advanceTime(1000)
        val updatedViewInfo = ViewInfo(
            name = "name 1 with update",
            windowTitle = "Name 1 Title",
            id = "id1",
            priority = ViewPriority.NORMAL,
            timeoutMs = 3000,
        )
        underTest.displayView(updatedViewInfo)

        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Name 1 Title")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // WHEN the id1 view with new information times out
        fakeClock.advanceTime(3001)

        // THEN the id1 view disappears and the old id1 information is never displayed
        verify(windowManager).removeView(viewCaptor.value)
        verify(windowManager, never()).addView(any(), any())
        assertThat(underTest.activeViews.size).isEqualTo(0)
    }

    @Test
    fun oldViewUpdatedWhileNewViewDisplayed_usesNewTimeout() {
        // First, display id1 view
        underTest.displayView(
            ViewInfo(
                name = "name 1",
                windowTitle = "Name 1 Title",
                id = "id1",
                priority = ViewPriority.NORMAL,
                timeoutMs = 5000,
            )
        )

        // Then, display a new id2 view
        fakeClock.advanceTime(1000)
        underTest.displayView(
            ViewInfo(
                name = "name 2",
                windowTitle = "Name 2 Title",
                id = "id2",
                priority = ViewPriority.NORMAL,
                timeoutMs = 3000,
            )
        )
        reset(windowManager)

        // While the id2 view is displayed, re-display the id1 view with new information *and a
        // longer timeout*
        fakeClock.advanceTime(1000)
        val updatedViewInfo = ViewInfo(
            name = "name 1 with update",
            windowTitle = "Name 1 Title",
            id = "id1",
            priority = ViewPriority.NORMAL,
            timeoutMs = 30000,
        )
        underTest.displayView(updatedViewInfo)

        val viewCaptor = argumentCaptor<View>()
        val windowParamsCaptor = argumentCaptor<WindowManager.LayoutParams>()
        verify(windowManager).addView(capture(viewCaptor), capture(windowParamsCaptor))
        assertThat(windowParamsCaptor.value.title).isEqualTo("Name 1 Title")
        assertThat(underTest.activeViews.size).isEqualTo(2)
        reset(windowManager)

        // WHEN id1's *old* timeout occurs
        fakeClock.advanceTime(3001)

        // THEN id1 is still displayed because it was updated with a new timeout
        verify(windowManager, never()).removeView(viewCaptor.value)
        assertThat(underTest.activeViews.size).isEqualTo(1)
    }

    @Test
    fun removeView_viewRemovedAndRemovalLogged() {
        // First, add the view
        underTest.displayView(getState())

        // Then, remove it
        val reason = "test reason"
        val deviceId = "id"
        underTest.removeView(deviceId, reason)

        verify(windowManager).removeView(any())
        verify(logger).logViewRemoval(deviceId, reason)
        verify(configurationController).removeCallback(any())
    }

    @Test
    fun removeView_noAdd_viewNotRemoved() {
        underTest.removeView("id", "reason")

        verify(windowManager, never()).removeView(any())
    }

    private fun getState(name: String = "name") = ViewInfo(name)

    private fun getConfigurationListener(): ConfigurationListener {
        val callbackCaptor = argumentCaptor<ConfigurationListener>()
        verify(configurationController).addCallback(capture(callbackCaptor))
        return callbackCaptor.value
    }

    inner class TestController(
        context: Context,
        logger: TemporaryViewLogger<ViewInfo>,
        windowManager: WindowManager,
        @Main mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        dumpManager: DumpManager,
        powerManager: PowerManager,
        wakeLockBuilder: WakeLock.Builder,
        systemClock: SystemClock,
    ) : TemporaryViewDisplayController<ViewInfo, TemporaryViewLogger<ViewInfo>>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        dumpManager,
        powerManager,
        R.layout.chipbar,
        wakeLockBuilder,
        systemClock,
    ) {
        var mostRecentViewInfo: ViewInfo? = null

        override val windowLayoutParams = commonWindowLayoutParams

        override fun updateView(newInfo: ViewInfo, currentView: ViewGroup) {
            mostRecentViewInfo = newInfo
        }

        override fun getTouchableRegion(view: View, outRect: Rect) {
            outRect.setEmpty()
        }

        override fun start() {}
    }

    data class ViewInfo(
        val name: String,
        override val windowTitle: String = "Window Title",
        override val wakeReason: String = "WAKE_REASON",
        override val timeoutMs: Int = TIMEOUT_MS.toInt(),
        override val id: String = "id",
        override val priority: ViewPriority = ViewPriority.NORMAL,
    ) : TemporaryViewInfo()
}

private const val TIMEOUT_MS = 10000L
