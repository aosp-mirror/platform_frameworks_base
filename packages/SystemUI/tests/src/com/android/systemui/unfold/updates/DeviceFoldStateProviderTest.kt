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

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ActivityType
import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.os.Handler
import android.testing.AndroidTestingRunner
import androidx.core.util.Consumer
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider.ScreenListener
import com.android.systemui.unfold.util.FoldableDeviceStates
import com.android.systemui.unfold.util.FoldableTestUtils
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DeviceFoldStateProviderTest : SysuiTestCase() {

    @Mock private lateinit var hingeAngleProvider: HingeAngleProvider

    @Mock private lateinit var screenStatusProvider: ScreenStatusProvider

    @Mock private lateinit var deviceStateManager: DeviceStateManager

    @Mock private lateinit var activityManager: ActivityManager

    @Mock private lateinit var handler: Handler

    @Captor private lateinit var foldStateListenerCaptor: ArgumentCaptor<FoldStateListener>

    @Captor private lateinit var screenOnListenerCaptor: ArgumentCaptor<ScreenListener>

    @Captor private lateinit var hingeAngleCaptor: ArgumentCaptor<Consumer<Float>>

    private lateinit var foldStateProvider: DeviceFoldStateProvider

    private val foldUpdates: MutableList<Int> = arrayListOf()
    private val hingeAngleUpdates: MutableList<Float> = arrayListOf()

    private lateinit var deviceStates: FoldableDeviceStates

    private var scheduledRunnable: Runnable? = null
    private var scheduledRunnableDelay: Long? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        deviceStates = FoldableTestUtils.findDeviceStates(context)

        foldStateProvider =
            DeviceFoldStateProvider(
                context,
                hingeAngleProvider,
                screenStatusProvider,
                deviceStateManager,
                activityManager,
                context.mainExecutor,
                handler)

        foldStateProvider.addCallback(
            object : FoldStateProvider.FoldUpdatesListener {
                override fun onHingeAngleUpdate(angle: Float) {
                    hingeAngleUpdates.add(angle)
                }

                override fun onFoldUpdate(update: Int) {
                    foldUpdates.add(update)
                }
            })
        foldStateProvider.start()

        verify(deviceStateManager).registerCallback(any(), foldStateListenerCaptor.capture())
        verify(screenStatusProvider).addCallback(screenOnListenerCaptor.capture())
        verify(hingeAngleProvider).addCallback(hingeAngleCaptor.capture())

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

        // By default, we're on launcher.
        setupForegroundActivityType(ACTIVITY_TYPE_HOME)
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
    fun testOnFolded_stopsHingeAngleProvider() {
        setFoldState(folded = true)

        verify(hingeAngleProvider).stop()
    }

    @Test
    fun testOnUnfolded_startsHingeAngleProvider() {
        setFoldState(folded = false)

        verify(hingeAngleProvider).start()
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

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE)
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
    fun startClosingEvent_afterTimeout_abortEmitted() {
        sendHingeAngleEvent(90)
        sendHingeAngleEvent(80)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS)

        assertThat(foldUpdates)
            .containsExactly(FOLD_UPDATE_START_CLOSING, FOLD_UPDATE_FINISH_HALF_OPEN)
    }

    @Test
    fun startClosingEvent_beforeTimeout_abortNotEmitted() {
        sendHingeAngleEvent(90)
        sendHingeAngleEvent(80)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS - 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_eventBeforeTimeout_oneEventEmitted() {
        sendHingeAngleEvent(180)
        sendHingeAngleEvent(90)

        simulateTimeout(HALF_OPENED_TIMEOUT_MILLIS - 1)
        sendHingeAngleEvent(80)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_timeoutAfterTimeoutRescheduled_abortEmitted() {
        sendHingeAngleEvent(180)
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
        sendHingeAngleEvent(180)

        sendHingeAngleEvent(90)
        sendHingeAngleEvent(80)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    @Test
    fun startClosingEvent_whileClosing_emittedDespiteInitialAngle() {
        val maxAngle = 180 - FULLY_OPEN_THRESHOLD_DEGREES.toInt()
        for (i in 1..maxAngle) {
            foldUpdates.clear()

            simulateFolding(startAngle = i)

            assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
            simulateTimeout() // Timeout to set the state to aborted.
        }
    }

    @Test
    fun startClosingEvent_whileNotOnLauncher_doesNotTriggerBeforeThreshold() {
        setupForegroundActivityType(ACTIVITY_TYPE_STANDARD)
        sendHingeAngleEvent(180)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES + 1)

        assertThat(foldUpdates).isEmpty()
    }

    @Test
    fun startClosingEvent_whileNotOnLauncher_triggersAfterThreshold() {
        setupForegroundActivityType(ACTIVITY_TYPE_STANDARD)
        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES)

        sendHingeAngleEvent(START_CLOSING_ON_APPS_THRESHOLD_DEGREES - 1)

        assertThat(foldUpdates).containsExactly(FOLD_UPDATE_START_CLOSING)
    }

    private fun setupForegroundActivityType(@ActivityType type: Int) {
        val taskInfo = RunningTaskInfo().apply { topActivityType = type }
        whenever(activityManager.getRunningTasks(1)).thenReturn(listOf(taskInfo))
    }

    private fun simulateTimeout(waitTime: Long = HALF_OPENED_TIMEOUT_MILLIS) {
        val runnableDelay = scheduledRunnableDelay ?: throw Exception("No runnable scheduled.")
        if (waitTime >= runnableDelay) {
            scheduledRunnable?.run()
            scheduledRunnable = null
            scheduledRunnableDelay = null
        }
    }

    private fun simulateFolding(startAngle: Int) {
        sendHingeAngleEvent(startAngle)
        sendHingeAngleEvent(startAngle - 1)
    }

    private fun setFoldState(folded: Boolean) {
        val state = if (folded) deviceStates.folded else deviceStates.unfolded
        foldStateListenerCaptor.value.onStateChanged(state)
    }

    private fun fireScreenOnEvent() {
        screenOnListenerCaptor.value.onScreenTurnedOn()
    }

    private fun sendHingeAngleEvent(angle: Int) {
        hingeAngleCaptor.value.accept(angle.toFloat())
    }
}
