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

package com.android.systemui.unfold.updates

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import androidx.core.util.Consumer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.config.ResourceUnfoldTransitionConfig
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.system.ActivityManagerActivityTypeProvider
import com.android.systemui.unfold.updates.FoldProvider.FoldCallback
import com.android.systemui.unfold.updates.RotationChangeProvider.RotationListener
import com.android.systemui.unfold.updates.hinge.FULLY_OPEN_DEGREES
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider.ScreenListener
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityProvider
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.fail
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class DeviceFoldStateProviderTest : SysuiTestCase() {

    @Mock private lateinit var activityTypeProvider: ActivityManagerActivityTypeProvider

    @Mock private lateinit var rotationChangeProvider: RotationChangeProvider

    @Mock private lateinit var unfoldKeyguardVisibilityProvider: UnfoldKeyguardVisibilityProvider

    @Mock private lateinit var resources: Resources

    @Mock private lateinit var handler: Handler

    @Mock private lateinit var mainLooper: Looper

    @Mock private lateinit var context: Context

    @Mock private lateinit var thread: Thread

    @Captor private lateinit var rotationListener: ArgumentCaptor<RotationListener>

    private val foldProvider = TestFoldProvider()
    private val screenOnStatusProvider = TestScreenOnStatusProvider()
    private val testHingeAngleProvider = TestHingeAngleProvider()

    private lateinit var foldStateProvider: DeviceFoldStateProvider

    private val foldUpdates: MutableList<Int> = arrayListOf()
    private val hingeAngleUpdates: MutableList<Float> = arrayListOf()
    private val unfoldedScreenAvailabilityUpdates: MutableList<Unit> = arrayListOf()

    private var scheduledRunnable: Runnable? = null
    private var scheduledRunnableDelay: Long? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val config =
            object : UnfoldTransitionConfig by ResourceUnfoldTransitionConfig() {
                override val halfFoldedTimeoutMillis: Int
                    get() = HALF_OPENED_TIMEOUT_MILLIS.toInt()
            }
        whenever(mainLooper.isCurrentThread).thenReturn(true)
        whenever(handler.looper).thenReturn(mainLooper)
        whenever(mainLooper.isCurrentThread).thenReturn(true)
        whenever(mainLooper.thread).thenReturn(thread)
        whenever(thread.name).thenReturn("backgroundThread")
        whenever(context.resources).thenReturn(resources)
        whenever(context.mainExecutor).thenReturn(mContext.mainExecutor)

        foldStateProvider =
            DeviceFoldStateProvider(
                    config,
                    context,
                    screenOnStatusProvider,
                    activityTypeProvider,
                    unfoldKeyguardVisibilityProvider,
                    foldProvider,
                    testHingeAngleProvider,
                    rotationChangeProvider,
                    handler
            )

        foldStateProvider.addCallback(
            object : FoldStateProvider.FoldUpdatesListener {
                override fun onHingeAngleUpdate(angle: Float) {
                    hingeAngleUpdates.add(angle)
                }

                override fun onFoldUpdate(update: Int) {
                    foldUpdates.add(update)
                }

                override fun onUnfoldedScreenAvailable() {
                    unfoldedScreenAvailabilityUpdates.add(Unit)
                }
            }
        )
        foldStateProvider.start()

        verify(rotationChangeProvider).addCallback(capture(rotationListener))

        whenever(handler.postDelayed(any<Runnable>(), any())).then { invocationOnMock ->
            scheduledRunnable = invocationOnMock.getArgument<Runnable>(0)
            scheduledRunnableDelay = invocationOnMock.getArgument<Long>(1)
            null
        }

        whenever(handler.removeCallbacks(any<Runnable>())).then { invocationOnMock ->
            val removedRunnable = invocationOnMock.getArgument<Runnable>(0)
            if (removedRunnable == scheduledRunnable) {
                scheduledRunnableDelay = null
                scheduledRunnable = null
            }
            null
        }

        whenever(handler.post(any<Runnable>())).then { invocationOnMock ->
            val runnable = invocationOnMock.getArgument<Runnable>(0)
            runnable.run()
            null
        }

        // By default, we're on launcher.
        setupForegroundActivityType(isHomeActivity = true)
        setIsLargeScreen(true)
    }

    @Test
    fun testOnFolded_emitsFinishClosedEvent() {
        setFoldState(folded = true)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_FINISH_CLOSED)
    }

    @Test
    fun testOnUnfolded_emitsStartOpeningEvent() {
        setFoldState(folded = false)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_OPENING)
    }

    @Test
    fun onUnfold_angleDecrBeforeInnerScrAvailable_emitsOnlyStartAndInnerScrAvailableEvents() {
        setFoldState(folded = true)
        foldUpdates.clear()

        setFoldState(folded = false)
        screenOnStatusProvider.notifyScreenTurningOn()
        sendHingeAngleEvent(10)
        sendHingeAngleEvent(20)
        sendHingeAngleEvent(10)
        screenOnStatusProvider.notifyScreenTurnedOn()

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_OPENING)
        assertThat(unfoldedScreenAvailabilityUpdates).hasSize(1)
    }

    @Test
    fun onUnfold_angleDecrAfterInnerScrAvailable_emitsStartInnerScrAvailableAndStartClosingEvnts() {
        setFoldState(folded = true)
        foldUpdates.clear()

        setFoldState(folded = false)
        screenOnStatusProvider.notifyScreenTurningOn()
        sendHingeAngleEvent(10)
        sendHingeAngleEvent(20)
        screenOnStatusProvider.notifyScreenTurnedOn()
        sendHingeAngleEvent(30)
        sendHingeAngleEvent(40)
        sendHingeAngleEvent(10)

        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_OPENING, FOLD_UPDATE_START_CLOSING)
        assertThat(unfoldedScreenAvailabilityUpdates).hasSize(1)
    }

    @Test
    fun testOnFolded_stopsHingeAngleProvider() {
        setFoldState(folded = true)

        assertThat(testHingeAngleProvider.isStarted).isFalse()
    }

    @Test
    fun testOnUnfolded_startsHingeAngleProvider() {
        setFoldState(folded = false)

        assertThat(testHingeAngleProvider.isStarted).isTrue()
    }

    @Test
    fun testFirstScreenOnEventWhenFolded_doesNotEmitEvents() {
        setFoldState(folded = true)
        foldUpdates.clear()

        fireScreenOnEvent()

        // Power button turn on
        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun testFirstScreenOnEventWhenUnfolded_doesNotEmitEvents() {
        setFoldState(folded = false)
        foldUpdates.clear()

        fireScreenOnEvent()

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun testFirstScreenOnEventAfterFoldAndUnfold_emitsUnfoldedScreenAvailableEvent() {
        setFoldState(folded = false)
        setFoldState(folded = true)
        fireScreenOnEvent()
        setFoldState(folded = false)
        foldUpdates.clear()

        fireScreenOnEvent()

        assertThat(unfoldedScreenAvailabilityUpdates).hasSize(1)
    }

    @Test
    fun testSecondScreenOnEventWhenUnfolded_doesNotEmitEvents() {
        setFoldState(folded = false)
        fireScreenOnEvent()
        foldUpdates.clear()

        fireScreenOnEvent()

        // No events as this is power button turn on
        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun testUnfoldedOpenedHingeAngleEmitted_isFinishedOpeningIsFalse() {
        setFoldState(folded = false)

        sendHingeAngleEvent(10)

        assertThat(foldStateProvider.isFinishedOpening).isFalse()
    }

    @Test
    fun testFoldedHalfOpenHingeAngleEmitted_isFinishedOpeningIsFalse() {
        setFoldState(folded = true)

        sendHingeAngleEvent(10)

        assertThat(foldStateProvider.isFinishedOpening).isFalse()
    }

    @Test
    fun testFoldedFullyOpenHingeAngleEmitted_isFinishedOpeningIsTrue() {
        setFoldState(folded = false)

        sendHingeAngleEvent(180)

        assertThat(foldStateProvider.isFinishedOpening).isTrue()
    }

    @Test
    fun testUnfoldedHalfOpenOpened_afterTimeout_isFinishedOpeningIsTrue() {
        setFoldState(folded = false)

        sendHingeAngleEvent(10)
        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS)

        assertThat(foldStateProvider.isFinishedOpening).isTrue()
    }

    @Test
    fun startClosingEvent_afterTimeout_finishHalfOpenEventEmitted() {
        setInitialHingeAngle(90)
        sendHingeAngleEvent(80)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS)

        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_CLOSING, FOLD_UPDATE_FINISH_HALF_OPEN)
    }

    @Test
    fun startClosingEvent_beforeTimeout_abortNotEmitted() {
        setInitialHingeAngle(90)
        sendHingeAngleEvent(80)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS - 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_eventBeforeTimeout_oneEventEmitted() {
        setInitialHingeAngle(180)
        sendHingeAngleEvent(90)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS - 1)
        sendHingeAngleEvent(80)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_timeoutAfterTimeoutRescheduled_finishHalfOpenStateEmitted() {
        setInitialHingeAngle(180)
        sendHingeAngleEvent(90)

        // The timeout should not trigger here.
        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS - 1)
        sendHingeAngleEvent(80)
        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS) // The timeout should trigger here.

        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_CLOSING, FOLD_UPDATE_FINISH_HALF_OPEN)
    }

    @Test
    fun startClosingEvent_shortTimeBetween_emitsOnlyOneEvents() {
        setInitialHingeAngle(180)

        sendHingeAngleEvent(90)
        sendHingeAngleEvent(80)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileClosing_emittedDespiteInitialAngle() {
        val maxAngle = 180 - FULLY_OPEN_THRESHOLD_DEGREES.toInt()
        val minAngle = Math.ceil(HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES.toDouble()).toInt() + 1
        for (startAngle in minAngle..maxAngle) {
            setInitialHingeAngle(startAngle)
            sendHingeAngleEvent(startAngle - HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES.toInt() - 1)

            assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
        }
    }

    @Test
    fun startClosingEvent_whileNotOnLauncher_doesNotTriggerBeforeThreshold() {
        setupForegroundActivityType(isHomeActivity = false)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun startClosingEvent_whileActivityTypeNotAvailable_triggerBeforeThreshold() {
        setupForegroundActivityType(isHomeActivity = null)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileOnLauncher_doesTriggerBeforeThreshold() {
        setupForegroundActivityType(isHomeActivity = true)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileNotOnLauncher_triggersAfterThreshold() {
        setupForegroundActivityType(isHomeActivity = false)
        setInitialHingeAngle(START_CLOSING_ON_APPS_THRESHOLD_DEGREES)

        sendHingeAngleEvent(
            START_CLOSING_ON_APPS_THRESHOLD_DEGREES -
                HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES.toInt() -
                1
        )

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileNotOnKeyguardAndNotOnLauncher_doesNotTriggerBeforeThreshold() {
        setKeyguardVisibility(visible = false)
        setupForegroundActivityType(isHomeActivity = false)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun startClosingEvent_whileKeyguardStateNotAvailable_triggerBeforeThreshold() {
        setKeyguardVisibility(visible = null)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileonKeyguard_doesTriggerBeforeThreshold() {
        setKeyguardVisibility(visible = true)
        setInitialHingeAngle(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startOnlyOnce_whenStartTriggeredThrice_startOnlyOnce() {
        foldStateProvider.start()
        foldStateProvider.start()
        foldStateProvider.start()

        assertThat(foldProvider.getNumberOfCallbacks()).isEqualTo(1)
    }

    @Test(expected = AssertionError::class)
    fun startMethod_whileNotOnMainThread_throwsException() {
        whenever(mainLooper.isCurrentThread).thenReturn(true)
        try {
            foldStateProvider.start()
            fail("Should have thrown AssertionError: should be called from the main thread.")
        } catch (e: AssertionError) {
            assertThat(e.message).contains("backgroundThread")
        }
    }

    @Test
    fun startClosingEvent_whileNotOnKeyguard_triggersAfterThreshold() {
        setKeyguardVisibility(visible = false)
        setInitialHingeAngle(START_CLOSING_ON_APPS_THRESHOLD_DEGREES)

        sendHingeAngleEvent(
            START_CLOSING_ON_APPS_THRESHOLD_DEGREES -
                HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES.toInt() -
                1
        )

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_doesNotTriggerBelowThreshold() {
        val thresholdAngle = (FULLY_OPEN_DEGREES - FULLY_OPEN_THRESHOLD_DEGREES).toInt()
        setInitialHingeAngle(180)
        sendHingeAngleEvent(thresholdAngle + 1)

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun startClosingEvent_triggersAfterThreshold() {
        val thresholdAngle = (FULLY_OPEN_DEGREES - FULLY_OPEN_THRESHOLD_DEGREES).toInt()
        setInitialHingeAngle(180)
        sendHingeAngleEvent(thresholdAngle + 1)
        sendHingeAngleEvent(thresholdAngle - 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_triggersAfterThreshold_fromHalfOpen() {
        setInitialHingeAngle(120)
        sendHingeAngleEvent((120 - HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES + 1).toInt())
        assertThat(foldUpdates).isEmpty()
        sendHingeAngleEvent((120 - HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES - 1).toInt())

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startOpeningAndClosingEvents_triggerWithOpenAndClose() {
        setInitialHingeAngle(120)
        sendHingeAngleEvent(130)
        sendHingeAngleEvent(120)
        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_OPENING, FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_notInterrupted_whenAngleIsSlightlyIncreased() {
        setInitialHingeAngle(120)
        sendHingeAngleEvent(110)
        sendHingeAngleEvent(111)
        sendHingeAngleEvent(100)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun screenOff_whileFolded_hingeAngleProviderRemainsOff() {
        setFoldState(folded = true)
        assertThat(testHingeAngleProvider.isStarted).isFalse()

        screenOnStatusProvider.notifyScreenTurningOff()

        assertThat(testHingeAngleProvider.isStarted).isFalse()
    }

    @Test
    fun screenOff_whileUnfolded_hingeAngleProviderStops() {
        setFoldState(folded = false)
        assertThat(testHingeAngleProvider.isStarted).isTrue()

        screenOnStatusProvider.notifyScreenTurningOff()

        assertThat(testHingeAngleProvider.isStarted).isFalse()
    }

    @Test
    fun screenOn_whileUnfoldedAndScreenOff_hingeAngleProviderStarted() {
        setFoldState(folded = false)
        screenOnStatusProvider.notifyScreenTurningOff()
        assertThat(testHingeAngleProvider.isStarted).isFalse()

        screenOnStatusProvider.notifyScreenTurningOn()

        assertThat(testHingeAngleProvider.isStarted).isTrue()
    }

    @Test
    fun screenOn_whileFolded_hingeAngleRemainsOff() {
        setFoldState(folded = true)
        assertThat(testHingeAngleProvider.isStarted).isFalse()

        screenOnStatusProvider.notifyScreenTurningOn()

        assertThat(testHingeAngleProvider.isStarted).isFalse()
    }

    @Test
    fun onRotationChanged_whileInProgress_cancelled() {
        setFoldState(folded = false)
        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_OPENING)

        rotationListener.value.onRotationChanged(1)

        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_OPENING, FOLD_UPDATE_FINISH_HALF_OPEN)
    }

    @Test
    fun onRotationChanged_whileNotInProgress_noUpdates() {
        setFoldState(folded = true)
        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_FINISH_CLOSED)

        rotationListener.value.onRotationChanged(1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_FINISH_CLOSED)
    }

    @Test
    fun onFolding_onSmallScreen_tansitionDoesNotStart() {
        setIsLargeScreen(false)

        setInitialHingeAngle(120)
        sendHingeAngleEvent(110)
        sendHingeAngleEvent(100)

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun onFolding_onLargeScreen_tansitionStarts() {
        setIsLargeScreen(true)

        setInitialHingeAngle(120)
        sendHingeAngleEvent(110)
        sendHingeAngleEvent(100)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun onUnfold_onSmallScreen_emitsStartOpening() {
        // the new display state might arrive later, so it shouldn't be used to decide to send the
        // start opening event, but only for the closing.
        setFoldState(folded = true)
        setIsLargeScreen(false)
        foldUpdates.clear()

        setFoldState(folded = false)
        screenOnStatusProvider.notifyScreenTurningOn()
        sendHingeAngleEvent(10)
        sendHingeAngleEvent(20)
        screenOnStatusProvider.notifyScreenTurnedOn()

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_OPENING)
    }

    private fun setupForegroundActivityType(isHomeActivity: Boolean?) {
        whenever(activityTypeProvider.isHomeActivity).thenReturn(isHomeActivity)
    }

    private fun setKeyguardVisibility(visible: Boolean?) {
        whenever(unfoldKeyguardVisibilityProvider.isKeyguardVisible).thenReturn(visible)
    }

    private fun simulateTimeout(waitTime: Long = HALF_OPENED_TIMEOUT_MILLIS) {
        val runnableDelay = scheduledRunnableDelay ?: throw Exception("No runnable scheduled.")
        if (waitTime >= runnableDelay) {
            scheduledRunnable?.run()
            scheduledRunnable = null
            scheduledRunnableDelay = null
        }
    }

    private fun setFoldState(folded: Boolean) {
        foldProvider.notifyFolded(folded)
    }

    private fun setIsLargeScreen(isLargeScreen: Boolean) {
        val smallestScreenWidth = if (isLargeScreen) { 601 } else { 10 }
        val configuration = Configuration()
        configuration.smallestScreenWidthDp = smallestScreenWidth
        whenever(resources.configuration).thenReturn(configuration)
    }

    private fun fireScreenOnEvent() {
        screenOnStatusProvider.notifyScreenTurnedOn()
    }

    private fun sendHingeAngleEvent(angle: Int) {
        testHingeAngleProvider.notifyAngle(angle.toFloat())
    }

    private fun setInitialHingeAngle(angle: Int) {
        setFoldState(angle == 0)
        sendHingeAngleEvent(angle)
        if (scheduledRunnableDelay != null) {
            simulateTimeout()
        }
        hingeAngleUpdates.clear()
        foldUpdates.clear()
        unfoldedScreenAvailabilityUpdates.clear()
    }

    private class TestFoldProvider : FoldProvider {
        private val callbacks = arrayListOf<FoldCallback>()

        override fun registerCallback(callback: FoldCallback, executor: Executor) {
            callbacks += callback
        }

        override fun unregisterCallback(callback: FoldCallback) {
            callbacks -= callback
        }

        fun notifyFolded(isFolded: Boolean) {
            callbacks.forEach { it.onFoldUpdated(isFolded) }
        }

        fun getNumberOfCallbacks(): Int {
            return callbacks.size
        }
    }

    private class TestScreenOnStatusProvider : ScreenStatusProvider {
        private val callbacks = arrayListOf<ScreenListener>()

        override fun addCallback(listener: ScreenListener) {
            callbacks += listener
        }

        override fun removeCallback(listener: ScreenListener) {
            callbacks -= listener
        }

        fun notifyScreenTurnedOn() {
            callbacks.forEach { it.onScreenTurnedOn() }
        }

        fun notifyScreenTurningOn() {
            callbacks.forEach { it.onScreenTurningOn() }
        }

        fun notifyScreenTurningOff() {
            callbacks.forEach { it.onScreenTurningOff() }
        }
    }

    private class TestHingeAngleProvider : HingeAngleProvider {
        private val callbacks = arrayListOf<Consumer<Float>>()
        var isStarted: Boolean = false

        override fun start() {
            isStarted = true
        }

        override fun stop() {
            isStarted = false
        }

        override fun addCallback(listener: Consumer<Float>) {
            callbacks += listener
        }

        override fun removeCallback(listener: Consumer<Float>) {
            callbacks -= listener
        }

        fun notifyAngle(angle: Float) {
            callbacks.forEach { it.accept(angle) }
        }
    }

    companion object {
        private const val HALF_OPENED_TIMEOUT_MILLIS = 300L
    }
}
