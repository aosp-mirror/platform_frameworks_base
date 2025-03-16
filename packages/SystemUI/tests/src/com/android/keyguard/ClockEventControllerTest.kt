/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.keyguard

import android.content.BroadcastReceiver
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder.MANUAL_DND_INACTIVE
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogcatOnlyMessageBuffer
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockTickRate
import com.android.systemui.plugins.clocks.ThemeConfig
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.plugins.clocks.ZenData.ZenMode
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.clearInvocations

@RunWith(AndroidJUnit4::class)
@SmallTest
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClockEventControllerTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val zenModeRepository = kosmos.fakeZenModeRepository
    private val testScope = kosmos.testScope

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private val mainExecutor = ImmediateExecutor()
    private lateinit var repository: FakeKeyguardRepository
    private val clockBuffers = ClockMessageBuffers(LogcatOnlyMessageBuffer(LogLevel.DEBUG))
    private lateinit var underTest: ClockEventController
    private lateinit var dndModeId: String

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var animations: ClockAnimations
    @Mock private lateinit var events: ClockEvents
    @Mock private lateinit var clock: ClockController
    @Mock private lateinit var bgExecutor: Executor
    @Mock private lateinit var smallClockController: ClockFaceController
    @Mock private lateinit var smallClockView: View
    @Mock private lateinit var smallClockViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var smallClockFrame: FrameLayout
    @Mock private lateinit var smallClockFrameViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var largeClockController: ClockFaceController
    @Mock private lateinit var largeClockView: View
    @Mock private lateinit var largeClockViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var smallClockEvents: ClockFaceEvents
    @Mock private lateinit var largeClockEvents: ClockFaceEvents
    @Mock private lateinit var parentView: View
    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var zenModeController: ZenModeController
    private var zenModeControllerCallback: ZenModeController.Callback? = null

    @Before
    fun setUp() {
        whenever(clock.smallClock).thenReturn(smallClockController)
        whenever(clock.largeClock).thenReturn(largeClockController)
        whenever(smallClockController.view).thenReturn(smallClockView)
        whenever(smallClockView.parent).thenReturn(smallClockFrame)
        whenever(smallClockView.viewTreeObserver).thenReturn(smallClockViewTreeObserver)
        whenever(smallClockFrame.viewTreeObserver).thenReturn(smallClockFrameViewTreeObserver)
        whenever(largeClockController.view).thenReturn(largeClockView)
        whenever(largeClockView.viewTreeObserver).thenReturn(largeClockViewTreeObserver)
        whenever(smallClockController.events).thenReturn(smallClockEvents)
        whenever(largeClockController.events).thenReturn(largeClockEvents)
        whenever(clock.events).thenReturn(events)
        whenever(smallClockController.animations).thenReturn(animations)
        whenever(largeClockController.animations).thenReturn(animations)
        whenever(smallClockController.config)
            .thenReturn(ClockFaceConfig(tickRate = ClockTickRate.PER_MINUTE))
        whenever(largeClockController.config)
            .thenReturn(ClockFaceConfig(tickRate = ClockTickRate.PER_MINUTE))
        whenever(smallClockController.theme).thenReturn(ThemeConfig(true, null))
        whenever(largeClockController.theme).thenReturn(ThemeConfig(true, null))
        whenever(userTracker.userId).thenReturn(1)

        dndModeId = MANUAL_DND_INACTIVE.id
        zenModeRepository.addMode(MANUAL_DND_INACTIVE)

        repository = FakeKeyguardRepository()

        val withDeps = KeyguardInteractorFactory.create(repository = repository)

        withDeps.featureFlags.apply { set(Flags.REGION_SAMPLING, false) }
        underTest =
            ClockEventController(
                withDeps.keyguardInteractor,
                keyguardTransitionInteractor,
                broadcastDispatcher,
                batteryController,
                keyguardUpdateMonitor,
                configurationController,
                context.resources,
                context,
                mainExecutor,
                bgExecutor,
                clockBuffers,
                withDeps.featureFlags,
                zenModeController,
                kosmos.zenModeInteractor,
                userTracker,
            )
        underTest.clock = clock

        runBlocking(IMMEDIATE) {
            underTest.registerListeners(parentView)

            repository.setIsDozing(true)
            repository.setDozeAmount(1f)
        }

        val zenCallbackCaptor = argumentCaptor<ZenModeController.Callback>()
        verify(zenModeController).addCallback(zenCallbackCaptor.capture())
        zenModeControllerCallback = zenCallbackCaptor.value
    }

    @Test
    fun clockSet_validateInitialization() {
        verify(clock).initialize(any(), anyFloat(), anyFloat())
    }

    @Test
    fun clockUnset_validateState() {
        underTest.clock = null

        assertEquals(underTest.clock, null)
    }

    @Test
    fun themeChanged_verifyClockPaletteUpdated() =
        runBlocking(IMMEDIATE) {
            verify(smallClockEvents).onThemeChanged(any())
            verify(largeClockEvents).onThemeChanged(any())

            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))
            captor.value.onThemeChanged()

            verify(smallClockEvents, times(2)).onThemeChanged(any())
            verify(largeClockEvents, times(2)).onThemeChanged(any())
        }

    @Test
    fun fontChanged_verifyFontSizeUpdated() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))
            captor.value.onDensityOrFontScaleChanged()

            verify(smallClockEvents, times(2)).onFontSettingChanged(anyFloat())
            verify(largeClockEvents, times(2)).onFontSettingChanged(anyFloat())
        }

    @Test
    fun batteryCallback_keyguardShowingCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, times(2)).charge()
        }

    @Test
    fun batteryCallback_keyguardShowingCharging_Duplicate_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, times(2)).charge()
        }

    @Test
    fun batteryCallback_keyguardHiddenCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(false)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, never()).charge()
        }

    @Test
    fun batteryCallback_keyguardShowingNotCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, false)

            verify(animations, never()).charge()
        }

    @Test
    fun localeCallback_verifyClockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<BroadcastReceiver>()
            verify(broadcastDispatcher)
                .registerReceiver(capture(captor), any(), eq(null), eq(null), anyInt(), eq(null))
            captor.value.onReceive(context, mock())

            verify(events).onLocaleChanged(any())
        }

    @Test
    @DisableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
    fun keyguardCallback_visibilityChanged_clockDozeCalled() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))

            captor.value.onKeyguardVisibilityChanged(true)
            verify(animations, never()).doze(0f)

            captor.value.onKeyguardVisibilityChanged(false)
            verify(animations, times(2)).doze(0f)
        }

    @Test
    fun keyguardCallback_timeFormat_clockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onTimeFormatChanged("12h")

            verify(events).onTimeFormatChanged(false)
        }

    @Test
    fun keyguardCallback_timezoneChanged_clockNotified() =
        runBlocking(IMMEDIATE) {
            val mockTimeZone = mock<TimeZone>()
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onTimeZoneChanged(mockTimeZone)

            verify(events).onTimeZoneChanged(mockTimeZone)
        }

    @Test
    fun keyguardCallback_userSwitched_clockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onUserSwitchComplete(10)

            verify(events).onTimeFormatChanged(false)
        }

    @Test
    fun keyguardCallback_verifyKeyguardChanged() =
        runBlocking(IMMEDIATE) {
            val job = underTest.listenForDozeAmount(this)
            repository.setDozeAmount(0.4f)

            yield()

            verify(animations, times(2)).doze(0.4f)

            job.cancel()
        }

    @Test
    fun listenForDozeAmountTransition_updatesClockDozeAmount() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(LOCKSCREEN, AOD)))
                .thenReturn(transitionStep)
            whenever(keyguardTransitionInteractor.transition(Edge.create(AOD, LOCKSCREEN)))
                .thenReturn(transitionStep)

            val job = underTest.listenForDozeAmountTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = LOCKSCREEN,
                    to = AOD,
                    value = 0.4f,
                    transitionState = TransitionState.RUNNING,
                )
            yield()

            verify(animations, times(2)).doze(0.4f)

            job.cancel()
        }

    @Test
    fun listenForTransitionToAodFromGone_updatesClockDozeAmountToOne() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(to = AOD)))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToAodTransition(this)
            transitionStep.value =
                TransitionStep(from = GONE, to = AOD, transitionState = TransitionState.STARTED)
            yield()

            verify(animations, times(2)).doze(1f)

            job.cancel()
        }

    @Test
    fun listenForTransitionToLSFromOccluded_updatesClockDozeAmountToZero() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(to = LOCKSCREEN)))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToLockscreenTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = OCCLUDED,
                    to = LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, times(2)).doze(0f)

            job.cancel()
        }

    @Test
    fun listenForTransitionToAodFromLockscreen_neverUpdatesClockDozeAmount() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(to = AOD)))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToAodTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = LOCKSCREEN,
                    to = AOD,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, never()).doze(1f)

            job.cancel()
        }

    @Test
    fun listenForAnyStateToLockscreenTransition_neverUpdatesClockDozeAmount() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(to = LOCKSCREEN)))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToLockscreenTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = AOD,
                    to = LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, never()).doze(0f)

            job.cancel()
        }

    @Test
    fun listenForAnyStateToDozingTransition_UpdatesClockDozeAmountToOne() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transition(Edge.create(to = DOZING)))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToDozingTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = LOCKSCREEN,
                    to = DOZING,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, times(2)).doze(1f)

            job.cancel()
        }

    @Test
    fun unregisterListeners_validate() =
        runBlocking(IMMEDIATE) {
            underTest.unregisterListeners()
            verify(broadcastDispatcher).unregisterReceiver(any())
            verify(configurationController).removeCallback(any())
            verify(batteryController).removeCallback(any())
            verify(keyguardUpdateMonitor).removeCallback(any())
            verify(smallClockController.view)
                .removeOnAttachStateChangeListener(underTest.smallClockOnAttachStateChangeListener)
            verify(largeClockController.view)
                .removeOnAttachStateChangeListener(underTest.largeClockOnAttachStateChangeListener)
        }

    @Test
    fun registerOnAttachStateChangeListener_validate() =
        runBlocking(IMMEDIATE) {
            verify(smallClockController.view)
                .addOnAttachStateChangeListener(underTest.smallClockOnAttachStateChangeListener)
            verify(largeClockController.view)
                .addOnAttachStateChangeListener(underTest.largeClockOnAttachStateChangeListener)
        }

    @Test
    fun registerAndRemoveOnGlobalLayoutListener_correctly() =
        runBlocking(IMMEDIATE) {
            underTest.smallClockOnAttachStateChangeListener!!.onViewAttachedToWindow(smallClockView)
            verify(smallClockFrame.viewTreeObserver).addOnGlobalLayoutListener(any())
            underTest.smallClockOnAttachStateChangeListener!!.onViewDetachedFromWindow(
                smallClockView
            )
            verify(smallClockFrame.viewTreeObserver).removeOnGlobalLayoutListener(any())
        }

    @Test
    fun registerOnGlobalLayoutListener_RemoveOnAttachStateChangeListener_correctly() =
        runBlocking(IMMEDIATE) {
            underTest.smallClockOnAttachStateChangeListener!!.onViewAttachedToWindow(smallClockView)
            verify(smallClockFrame.viewTreeObserver).addOnGlobalLayoutListener(any())
            underTest.unregisterListeners()
            verify(smallClockFrame.viewTreeObserver).removeOnGlobalLayoutListener(any())
        }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_UI)
    fun listenForDnd_onDndChange_updatesClockZenMode() =
        testScope.runTest {
            underTest.listenForDnd(testScope.backgroundScope)
            runCurrent()
            clearInvocations(events)

            zenModeRepository.activateMode(dndModeId)
            runCurrent()

            verify(events)
                .onZenDataChanged(
                    eq(ZenData(ZenMode.IMPORTANT_INTERRUPTIONS, R.string::dnd_is_on.name))
                )

            zenModeRepository.deactivateMode(dndModeId)
            runCurrent()

            verify(events).onZenDataChanged(eq(ZenData(ZenMode.OFF, R.string::dnd_is_off.name)))
        }

    @Test
    @DisableFlags(android.app.Flags.FLAG_MODES_UI)
    fun zenModeControllerCallback_onDndChange_updatesClockZenMode() =
        runBlocking(IMMEDIATE) {
            zenModeControllerCallback!!.onZenChanged(
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            )

            verify(events)
                .onZenDataChanged(
                    eq(ZenData(ZenMode.IMPORTANT_INTERRUPTIONS, R.string::dnd_is_on.name))
                )

            zenModeControllerCallback!!.onZenChanged(Settings.Global.ZEN_MODE_OFF)

            verify(events).onZenDataChanged(eq(ZenData(ZenMode.OFF, R.string::dnd_is_off.name)))
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}

private class ImmediateExecutor : DelayableExecutor {
    override fun execute(runnable: Runnable) {
        runnable.run()
    }

    override fun executeDelayed(runnable: Runnable, delay: Long, unit: TimeUnit) =
        runnable.apply { run() }

    override fun executeAtTime(runnable: Runnable, uptimeMillis: Long, unit: TimeUnit) =
        runnable.apply { run() }
}
