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

import android.graphics.Insets
import android.graphics.Rect
import android.os.Process
import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.statusbar.BatteryStatusChip
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimationQueued
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.RunningChipAnim
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.ShowingPersistentDot
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class SystemStatusAnimationSchedulerImplTest : SysuiTestCase() {

    @Mock private lateinit var systemEventCoordinator: SystemEventCoordinator

    @Mock private lateinit var statusBarWindowController: StatusBarWindowController
    @Mock private lateinit var statusBarWindowControllerStore: StatusBarWindowControllerStore

    @Mock private lateinit var statusBarContentInsetProvider: StatusBarContentInsetsProvider

    @Mock private lateinit var dumpManager: DumpManager

    @Mock private lateinit var listener: SystemStatusAnimationCallback

    @Mock private lateinit var logger: SystemStatusAnimationSchedulerLogger

    private lateinit var systemClock: FakeSystemClock
    private lateinit var chipAnimationController: SystemEventChipAnimationController
    private lateinit var systemStatusAnimationScheduler: SystemStatusAnimationScheduler

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(statusBarWindowControllerStore.defaultDisplay)
            .thenReturn(statusBarWindowController)
        systemClock = FakeSystemClock()
        chipAnimationController =
            SystemEventChipAnimationControllerImpl(
                mContext,
                statusBarWindowController,
                statusBarContentInsetProvider,
            )

        // StatusBarContentInsetProvider is mocked. Ensure that it returns some mocked values.
        whenever(statusBarContentInsetProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 0))
        whenever(statusBarContentInsetProvider.getStatusBarContentAreaForCurrentRotation())
            .thenReturn(Rect(/* left= */ 10, /* top= */ 10, /* right= */ 990, /* bottom= */ 100))

        // StatusBarWindowController is mocked. The addViewToWindow function needs to be mocked to
        // ensure that the chip view is added to a parent view
        whenever(statusBarWindowController.addViewToWindow(any(), any())).then {
            val statusbarFake = FrameLayout(mContext)
            statusbarFake.layout(/* l= */ 0, /* t= */ 0, /* r= */ 1000, /* b= */ 100)
            statusbarFake.addView(
                it.arguments[0] as View,
                it.arguments[1] as FrameLayout.LayoutParams,
            )
        }
    }

    @Test
    fun testBatteryStatusEvent_standardAnimationLifecycle() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        val batteryChip = createAndScheduleFakeBatteryEvent()

        // assert that animation is queued
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)
        // status chip starts animating in after debounce delay
        assertEquals(AnimatingIn, systemStatusAnimationScheduler.animationState.value)
        assertEquals(0f, batteryChip.contentView.alpha)
        assertEquals(0f, batteryChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationBegin()

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION)
        // assert that status chip is visible
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, batteryChip.contentView.alpha)
        assertEquals(1f, batteryChip.view.alpha)

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        // assert that it is still visible but switched to the AnimatingOut state
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, batteryChip.contentView.alpha)
        assertEquals(1f, batteryChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationFinish(false)

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        // assert that it is not visible anymore
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
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
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)
    }

    @Test
    fun testPrivacyStatusEvent_standardAnimationLifecycle() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        val privacyChip = createAndScheduleFakePrivacyEvent()

        // assert that animation is queued
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)
        // status chip starts animating in after debounce delay
        assertEquals(AnimatingIn, systemStatusAnimationScheduler.animationState.value)
        assertEquals(0f, privacyChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationBegin()

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION + 1)
        // assert that status chip is visible
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, privacyChip.view.alpha)

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        // assert that it is still visible but switched to the AnimatingOut state
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, privacyChip.view.alpha)
        verify(listener, times(1)).onSystemEventAnimationFinish(true)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // skip transition to persistent dot
        advanceTimeBy(DISAPPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        // assert that it the dot is now visible
        assertEquals(ShowingPersistentDot, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, privacyChip.view.alpha)

        // notify SystemStatusAnimationScheduler to remove persistent dot
        systemStatusAnimationScheduler.removePersistentDot()
        // assert that Idle state is entered
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
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
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // assert that animation is queued
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // skip debounce delay and appear animation duration
        fastForwardAnimationToState(RunningChipAnim)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testHighPriorityEvent_cancelsCurrentlyDisplayedLowPriorityEvent() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule low priority event
        val batteryChip = createAndScheduleFakeBatteryEvent()

        // fast forward to RunningChipAnim state
        fastForwardAnimationToState(RunningChipAnim)

        // assert that chip is displayed
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, batteryChip.view.alpha)

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // ensure that the event cancellation coroutine is started by the test scope
        testScheduler.runCurrent()

        // assert that currently displayed chip is immediately animated out
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // assert that high priority privacy chip animation is queued
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // skip debounce delay and appear animation
        advanceTimeBy(DEBOUNCE_DELAY + APPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
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
        assertEquals(AnimatingIn, systemStatusAnimationScheduler.animationState.value)

        // create and schedule high priority event
        val privacyChip = createAndScheduleFakePrivacyEvent()

        // ensure that the event cancellation coroutine is started by the test scope
        testScheduler.runCurrent()

        // assert that currently animated chip keeps animating
        assertEquals(AnimatingIn, systemStatusAnimationScheduler.animationState.value)

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION + 1)

        // assert that low priority chip is animated out immediately after finishing the appear
        // animation
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // assert that high priority privacy chip animation is queued
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)

        // skip debounce delay and appear animation
        advanceTimeBy(DEBOUNCE_DELAY + APPEAR_ANIMATION_DURATION + 1)
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)

        // high priority status chip is visible while low priority status chip is not visible
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
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
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        assertEquals(1f, privacyChip.view.alpha)
        assertEquals(0f, batteryChip.view.alpha)
    }

    @Test
    fun testPrivacyDot_isRemoved() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to ShowingPersistentDot state
        fastForwardAnimationToState(ShowingPersistentDot)
        assertEquals(ShowingPersistentDot, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // remove persistent dot and verify that animationState changes to Idle
        systemStatusAnimationScheduler.removePersistentDot()
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onHidePersistentDot()
    }

    @Test
    fun testAccessibilityAnnouncement_announced() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)
        val accessibilityDesc = "Some desc"
        val mockView = mock<View>()
        val mockAnimatableView =
            mock<BackgroundAnimatableView> { whenever(it.view).thenReturn(mockView) }

        scheduleFakeEventWithView(
            accessibilityDesc,
            mockAnimatableView,
            shouldAnnounceAccessibilityEvent = true,
        )
        fastForwardAnimationToState(AnimatingOut)

        verify(mockView).announceForAccessibility(eq(accessibilityDesc))
    }

    @Test
    fun testAccessibilityAnnouncement_nullDesc_noAnnouncement() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)
        val accessibilityDesc = null
        val mockView = mock<View>()
        val mockAnimatableView =
            mock<BackgroundAnimatableView> { whenever(it.view).thenReturn(mockView) }

        scheduleFakeEventWithView(
            accessibilityDesc,
            mockAnimatableView,
            shouldAnnounceAccessibilityEvent = true,
        )
        fastForwardAnimationToState(AnimatingOut)

        verify(mockView, never()).announceForAccessibility(any())
    }

    @Test
    fun testAccessibilityAnnouncement_notNeeded_noAnnouncement() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)
        val accessibilityDesc = "something"
        val mockView = mock<View>()
        val mockAnimatableView =
            mock<BackgroundAnimatableView> { whenever(it.view).thenReturn(mockView) }

        scheduleFakeEventWithView(
            accessibilityDesc,
            mockAnimatableView,
            shouldAnnounceAccessibilityEvent = false,
        )
        fastForwardAnimationToState(AnimatingOut)

        verify(mockView, never()).announceForAccessibility(any())
    }

    @Test
    fun testPrivacyDot_isRemovedDuringChipAnimation() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to RunningChipAnim state
        fastForwardAnimationToState(RunningChipAnim)
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // skip display time and verify that disappear animation is run
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)

        // skip disappear animation and verify that animationState changes to Idle instead of
        // ShowingPersistentDot
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
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

        // fast forward to AnimatingOut state
        fastForwardAnimationToState(AnimatingOut)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // remove persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // verify that the onHidePersistentDot callback is invoked
        verify(listener, times(1)).onHidePersistentDot()

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        testScheduler.runCurrent()

        // verify that animationState changes to Idle
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
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

        // skip chip animation lifecycle and fast forward to ShowingPersistentDot state
        fastForwardAnimationToState(ShowingPersistentDot)

        // verify that we reach ShowingPersistentDot and that listener callback is invoked
        assertEquals(ShowingPersistentDot, systemStatusAnimationScheduler.animationState.value)
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
        fastForwardAnimationToState(RunningChipAnim)

        // create and schedule a privacy event again (resets forceVisible to true)
        createAndScheduleFakePrivacyEvent()

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemEventAnimationFinish(anyBoolean())

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)

        // verify that we reach ShowingPersistentDot and that listener callback is invoked
        assertEquals(ShowingPersistentDot, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())
    }

    @Test
    fun testNewEvent_isScheduled_whenPostedDuringRemovalAnimation() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to AnimatingOut state
        fastForwardAnimationToState(AnimatingOut)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // schedule another high priority event while the event is animating out
        createAndScheduleFakePrivacyEvent()

        // verify that the state is still AnimatingOut
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)

        // skip disappear animation duration and verify that new state is AnimationQueued
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
        testScheduler.runCurrent()
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)
        // also verify that onHidePersistentDot callback is called
        verify(listener, times(1)).onHidePersistentDot()
    }

    @Test
    fun testDotIsRemoved_evenIfAnimatorCallbackIsDelayed() = runTest {
        // Instantiate class under test with TestScope from runTest
        initializeSystemStatusAnimationScheduler(testScope = this)

        // create and schedule high priority event
        createAndScheduleFakePrivacyEvent()

        // skip chip animation lifecycle and fast forward to AnimatingOut state
        fastForwardAnimationToState(AnimatingOut)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemStatusAnimationTransitionToPersistentDot(any())

        // request removal of persistent dot
        systemStatusAnimationScheduler.removePersistentDot()

        // verify that the state is still AnimatingOut
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)

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
        // verify that animationState is Idle
        assertEquals(Idle, systemStatusAnimationScheduler.animationState.value)
    }

    private fun TestScope.fastForwardAnimationToState(animationState: SystemEventAnimationState) {
        // this function should only be called directly after posting a status event
        assertEquals(AnimationQueued, systemStatusAnimationScheduler.animationState.value)
        if (animationState == Idle || animationState == AnimationQueued) return
        // skip debounce delay
        advanceTimeBy(DEBOUNCE_DELAY + 1)

        // status chip starts animating in after debounce delay
        assertEquals(AnimatingIn, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemEventAnimationBegin()
        if (animationState == AnimatingIn) return

        // skip appear animation
        animatorTestRule.advanceTimeBy(APPEAR_ANIMATION_DURATION)
        advanceTimeBy(APPEAR_ANIMATION_DURATION)
        assertEquals(RunningChipAnim, systemStatusAnimationScheduler.animationState.value)
        if (animationState == RunningChipAnim) return

        // skip status chip display time
        advanceTimeBy(DISPLAY_LENGTH + 1)
        assertEquals(AnimatingOut, systemStatusAnimationScheduler.animationState.value)
        verify(listener, times(1)).onSystemEventAnimationFinish(anyBoolean())
        if (animationState == AnimatingOut) return

        // skip disappear animation
        animatorTestRule.advanceTimeBy(DISAPPEAR_ANIMATION_DURATION)
    }

    private fun createAndScheduleFakePrivacyEvent(): OngoingPrivacyChip {
        val privacyChip = OngoingPrivacyChip(mContext)
        val fakePrivacyStatusEvent = FakePrivacyStatusEvent(viewCreator = { privacyChip })
        systemStatusAnimationScheduler.onStatusEvent(fakePrivacyStatusEvent)
        return privacyChip
    }

    private fun scheduleFakeEventWithView(
        desc: String?,
        view: BackgroundAnimatableView,
        shouldAnnounceAccessibilityEvent: Boolean,
    ) {
        val fakeEvent =
            FakeStatusEvent(
                viewCreator = { view },
                contentDescription = desc,
                shouldAnnounceAccessibilityEvent = shouldAnnounceAccessibilityEvent,
            )
        systemStatusAnimationScheduler.onStatusEvent(fakeEvent)
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
                statusBarWindowControllerStore,
                dumpManager,
                systemClock,
                CoroutineScope(StandardTestDispatcher(testScope.testScheduler)),
                logger,
            )
        // add a mock listener
        systemStatusAnimationScheduler.addCallback(listener)

        if (advancePastMinUptime) {
            // ensure that isTooEarly() check in SystemStatusAnimationScheduler does not return true
            systemClock.advanceTime(Process.getStartUptimeMillis() + MIN_UPTIME)
        }
    }
}
