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

package com.android.systemui.statusbar.events

import android.graphics.Rect
import android.os.Process
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.widget.FrameLayout
import androidx.core.animation.AnimatorTestRule
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.statusbar.BatteryStatusChip
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class SystemStatusAnimationSchedulerImplTest : SysuiTestCase() {

    @Mock private lateinit var systemEventCoordinator: SystemEventCoordinator

    @Mock private lateinit var statusBarWindowController: StatusBarWindowController

    @Mock private lateinit var statusBarContentInsetProvider: StatusBarContentInsetsProvider

    @Mock private lateinit var dumpManager: DumpManager

    @Mock private lateinit var listener: SystemStatusAnimationCallback

    private lateinit var systemClock: FakeSystemClock
    private lateinit var chipAnimationController: SystemEventChipAnimationController
    private lateinit var systemStatusAnimationScheduler: SystemStatusAnimationScheduler
    private val fakeFeatureFlags = FakeFeatureFlags()

    @get:Rule val animatorTestRule = AnimatorTestRule()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        fakeFeatureFlags.set(Flags.PLUG_IN_STATUS_BAR_CHIP, true)

        systemClock = FakeSystemClock()
        chipAnimationController =
            SystemEventChipAnimationController(
                mContext,
                statusBarWindowController,
                statusBarContentInsetProvider,
                fakeFeatureFlags
            )

        // StatusBarContentInsetProvider is mocked. Ensure that it returns some mocked values.
        whenever(statusBarContentInsetProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(android.util.Pair(10, 10))
        whenever(statusBarContentInsetProvider.getStatusBarContentAreaForCurrentRotation())
            .thenReturn(Rect(10, 0, 990, 100))

        // StatusBarWindowController is mocked. The addViewToWindow function needs to be mocked to
        // ensure that the chip view is added to a parent view
        whenever(statusBarWindowController.addViewToWindow(any(), any())).then {
            val statusbarFake = FrameLayout(mContext)
            statusbarFake.layout(0, 0, 1000, 100)
            statusbarFake.addView(
                it.arguments[0] as View,
                it.arguments[1] as FrameLayout.LayoutParams
            )
        }
    }

    @Test
    fun testBatteryStatusEvent_standardAnimationLifecycle() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        val batteryChip = createAndScheduleFakeBatteryEvent()

        // assert that animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)
        // status chip starts animating in after debounce delay
        assertEquals(ANIMATING_IN, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(0f, batteryChip.contentView.alpha)
        assertEquals(0f, batteryChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationBegin()

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION)
        // assert that status chip is visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, batteryChip.contentView.alpha)
        assertEquals(1f, batteryChip.view.alpha)

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        // assert that it is still visible but switched to the ANIMATING_OUT state
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, batteryChip.contentView.alpha)
        assertEquals(1f, batteryChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationFinish(false)

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        // assert that it is not visible anymore
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(0f, batteryChip.contentView.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    /** Regression test for b/294104969. */
    @Test
    fun testPrivacyStatusEvent_beforeSystemUptime_stillDisplayed() = runTest {
        initializeSystemStatusAnimationScheduler(testScope = this, advancePastMinUptime = false)

        // WHEN the uptime hasn't quite passed the minimum required uptime...
        systemClock.setUptimeMillis(Process.getStartUptimeMillis() + MIN_UPTIME / 2)

        // BUT the event is a privacy event
        createAndScheduleFakePrivacyEvent()

        // THEN the privacy event still happens
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())
    }

    @Test
    fun testPrivacyStatusEvent_standardAnimationLifecycle() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        val privacyChip = createAndScheduleFakePrivacyEvent()

        // assert that animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)
        // status chip starts animating in after debounce delay
        assertEquals(ANIMATING_IN, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(0f, privacyChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationBegin()

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION + 1)
        // assert that status chip is visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        // assert that it is still visible but switched to the ANIMATING_OUT state
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationFinish(true)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // skip transition to persistent dot
        advanceTimeBy(DISAPPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        // assert that it the dot is now visible
        assertEquals(SHOWING_PERSISTENT_DOT, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)

        // notify SystemStatusAnimationScheduler to remove persistent dot
        systemStatusAnimationScheduler.removePersistentDot()
        // assert that IDLE state is entered
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onHidePersistentDot()
    }

    @Test
    fun testHighPriorityEvent_takesPrecedenceOverScheduledLowPriorityEvent() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule low priority event
        val batteryChip = createAndScheduleFakeBatteryEvent()
        batteryChip.view.alpha = 0f

        // assert that animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // assert that animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // skip debounce delay and appear animation duration
        fastForwardAnimationToState(RUNNING_CHIP_ANIM)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testHighPriorityEvent_cancelsCurrentlyDisplayedLowPriorityEvent() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule low priority event
        val batteryChip = createAndScheduleFakeBatteryEvent()

        // fast forward to RUNNING_CHIP_ANIM state
        fastForwardAnimationToState(RUNNING_CHIP_ANIM)

        // assert that chip is displayed
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, batteryChip.view.alpha)

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // ensure that the event cancellation coroutine is started by the test scope
        testScheduler.runCurrent()

        // assert that currently displayed chip is immediately animated out
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // assert that high priority privacy chip animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // skip debounce delay and appear animation
        advanceTimeBy(DEBOUNCE_DELAY + APPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testHighPriorityEvent_cancelsCurrentlyAnimatedLowPriorityEvent() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule low priority event
        val batteryChip = createAndScheduleFakeBatteryEvent()

        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)

        // assert that chip is animated in
        assertEquals(ANIMATING_IN, systemStatusAnimationScheduler.getAnimationState())

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // ensure that the event cancellation coroutine is started by the test scope
        testScheduler.runCurrent()

        // assert that currently animated chip keeps animating
        assertEquals(ANIMATING_IN, systemStatusAnimationScheduler.getAnimationState())

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION + 1)

        // assert that low priority chip is animated out immediately after finishing the appear
        // animation
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // assert that high priority privacy chip animation is queued
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())

        // skip debounce delay and appear animation
        advanceTimeBy(DEBOUNCE_DELAY + APPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testHighPriorityEvent_isNotReplacedByLowPriorityEvent() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // create and schedule low priority event
        val batteryChip = createAndScheduleFakeBatteryEvent()
        batteryChip.view.alpha = 0f

        // skip debounce delay and appear animation
        advanceTimeBy(DEBOUNCE_DELAY + APPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testPrivacyDot_isRemoved() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to SHOWING_PERSISTENT_DOT state
        fastForwardAnimationToState(SHOWING_PERSISTENT_DOT)
        assertEquals(SHOWING_PERSISTENT_DOT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // remove persistent dot and verify that animationState changes to IDLE
        systemStatusAnimationScheduler.removePersistentDot()
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onHidePersistentDot()
    }

    @Test
    fun testPrivacyDot_isRemovedDuringChipAnimation() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to RUNNING_CHIP_ANIM state
        fastForwardAnimationToState(RUNNING_CHIP_ANIM)
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // skip display time and verify that disappear animation is run
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())

        // skip disappear animation and verify that animationState changes to IDLE instead of
        // SHOWING_PERSISTENT_DOT
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
        // verify that the persistent dot callbacks are not invoked
        verify(listener, never()).onSystemStatusAnimationTransitionToPersistentDot(any())
        verify(listener, never()).onHidePersistentDot()
    }

    @Test
    fun testPrivacyDot_isRemovedDuringChipDisappearAnimation() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // fast forward to ANIMATING_OUT state
        fastForwardAnimationToState(ANIMATING_OUT)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // remove persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // verify that the onHidePersistentDot callback is invoked
        verify(listener, times(1)).onHidePersistentDot()

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        testScheduler.runCurrent()

        // verify that animationState changes to IDLE
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
    }

    @Test
    fun testPrivacyEvent_forceVisibleIsUpdated_whenRescheduledDuringQueuedState() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule privacy event
        createAndScheduleFakePrivacyEvent()
        // request removal of persistent dot (sets forceVisible to false)
        systemStatusAnimationScheduler.removePersistentDot()
        // create and schedule a privacy event again (resets forceVisible to true)
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to SHOWING_PERSISTENT_DOT state
        fastForwardAnimationToState(SHOWING_PERSISTENT_DOT)

        // verify that we reach SHOWING_PERSISTENT_DOT and that listener callback is invoked
        assertEquals(SHOWING_PERSISTENT_DOT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())
    }

    @Test
    fun testPrivacyEvent_forceVisibleIsUpdated_whenRescheduledDuringAnimatingState() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule privacy event
        createAndScheduleFakePrivacyEvent()
        // request removal of persistent dot (sets forceVisible to false)
        systemStatusAnimationScheduler.removePersistentDot()
        fastForwardAnimationToState(RUNNING_CHIP_ANIM)

        // create and schedule a privacy event again (resets forceVisible to true)
        createAndScheduleFakePrivacyEvent()

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemEventAnimationFinish(anyBoolean())

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // verify that we reach SHOWING_PERSISTENT_DOT and that listener callback is invoked
        assertEquals(SHOWING_PERSISTENT_DOT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())
    }

    @Test
    fun testNewEvent_isScheduled_whenPostedDuringRemovalAnimation() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to ANIMATING_OUT state
        fastForwardAnimationToState(ANIMATING_OUT)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // schedule another high priority event while the event is animating out
        createAndScheduleFakePrivacyEvent()

        // verify that the state is still ANIMATING_OUT
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())

        // skip disappear animation duration and verify that new state is ANIMATION_QUEUED
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        testScheduler.runCurrent()
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())
        // also verify that onHidePersistentDot callback is called
        verify(listener, times(1)).onHidePersistentDot()
    }

    @Test
    fun testDotIsRemoved_evenIfAnimatorCallbackIsDelayed() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to ANIMATING_OUT state
        fastForwardAnimationToState(ANIMATING_OUT)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // verify that the state is still ANIMATING_OUT
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())

        // skip disappear animation duration
        testScheduler.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION + 1)
        // In an old implementation this would trigger a coroutine timeout causing the
        // onHidePersistentDot callback to be missed.
        testScheduler.runCurrent()

        // advance animator time to invoke onAnimationEnd callback
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        testScheduler.runCurrent()

        // verify that onHidePersistentDot is invoked despite the animator callback being delayed
        // (it's invoked more than DISAPPEAR_ANIMATION_DURATION after the dot removal was requested)
        verify(listener, times(1)).onHidePersistentDot()
        // verify that animationState is IDLE
        assertEquals(IDLE, systemStatusAnimationScheduler.getAnimationState())
    }

    private fun TestScope.fastForwardAnimationToState(@SystemAnimationState animationState: Int) {
        // this function should only be called directly after posting a status event
        assertEquals(ANIMATION_QUEUED, systemStatusAnimationScheduler.getAnimationState())
        if (animationState == IDLE || animationState == ANIMATION_QUEUED) return
        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)

        // status chip starts animating in after debounce delay
        assertEquals(ANIMATING_IN, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemEventAnimationBegin()
        if (animationState == ANIMATING_IN) return

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION)
        assertEquals(RUNNING_CHIP_ANIM, systemStatusAnimationScheduler.getAnimationState())
        if (animationState == RUNNING_CHIP_ANIM) return

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(ANIMATING_OUT, systemStatusAnimationScheduler.getAnimationState())
        verify(listener, times(1)).onSystemEventAnimationFinish(anyBoolean())
        if (animationState == ANIMATING_OUT) return

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
    }

    private fun createAndScheduleFakePrivacyEvent(): OngoingPrivacyChip {
        val privacyChip = OngoingPrivacyChip(mContext)
        val fakePrivacyStatusEvent = FakePrivacyStatusEvent(viewCreator = { privacyChip })
        systemStatusAnimationScheduler.onStatusEvent(fakePrivacyStatusEvent)
        return privacyChip
    }

    private fun createAndScheduleFakeBatteryEvent(): BatteryStatusChip {
        val batteryChip = BatteryStatusChip(mContext)
        val fakeBatteryEvent =
            FakeStatusEvent(viewCreator = { batteryChip }, priority = 50, forceVisible = false)
        systemStatusAnimationScheduler.onStatusEvent(fakeBatteryEvent)
        return batteryChip
    }

    private fun initializeSystemStatusAnimationScheduler(
        testScope: TestScope,
        advancePastMinUptime: Boolean = true,
    ) {
        systemStatusAnimationScheduler =
            SystemStatusAnimationSchedulerImpl(
                systemEventCoordinator,
                chipAnimationController,
                statusBarWindowController,
                dumpManager,
                systemClock,
                CoroutineScope(StandardTestDispatcher(testScope.testScheduler))
            )
        // add a mock listener
        systemStatusAnimationScheduler.addCallback(listener)

        if (advancePastMinUptime) {
            // ensure that isTooEarly() check in SystemStatusAnimationScheduler does not return true
            systemClock.advanceTime(Process.getStartUptimeMillis() + MIN_UPTIME)
        }
    }
}
