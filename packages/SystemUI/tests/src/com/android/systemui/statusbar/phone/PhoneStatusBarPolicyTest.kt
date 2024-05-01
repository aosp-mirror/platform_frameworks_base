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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResourcesManager
import android.content.SharedPreferences
import android.os.UserManager
import android.telecom.TelecomManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.DataSaverController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.statusbar.policy.SensorPrivacyController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class PhoneStatusBarPolicyTest : SysuiTestCase() {

    companion object {
        private const val ALARM_SLOT = "alarm"
        private const val CONNECTED_DISPLAY_SLOT = "connected_display"
        private const val MANAGED_PROFILE_SLOT = "managed_profile"
    }

    @Mock private lateinit var iconController: StatusBarIconController
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var castController: CastController
    @Mock private lateinit var hotspotController: HotspotController
    @Mock private lateinit var bluetoothController: BluetoothController
    @Mock private lateinit var nextAlarmController: NextAlarmController
    @Mock private lateinit var userInfoController: UserInfoController
    @Mock private lateinit var rotationLockController: RotationLockController
    @Mock private lateinit var dataSaverController: DataSaverController
    @Mock private lateinit var zenModeController: ZenModeController
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var locationController: LocationController
    @Mock private lateinit var sensorPrivacyController: SensorPrivacyController
    @Mock private lateinit var alarmManager: AlarmManager
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var devicePolicyManagerResources: DevicePolicyResourcesManager
    @Mock private lateinit var recordingController: RecordingController
    @Mock private lateinit var telecomManager: TelecomManager
    @Mock private lateinit var sharedPreferences: SharedPreferences
    @Mock private lateinit var dateFormatUtil: DateFormatUtil
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var ringerModeTracker: RingerModeTracker
    @Mock private lateinit var privacyItemController: PrivacyItemController
    @Mock private lateinit var privacyLogger: PrivacyLogger
    @Captor
    private lateinit var alarmCallbackCaptor:
        ArgumentCaptor<NextAlarmController.NextAlarmChangeCallback>

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val fakeConnectedDisplayStateProvider = FakeConnectedDisplayStateProvider()

    private lateinit var executor: FakeExecutor
    private lateinit var statusBarPolicy: PhoneStatusBarPolicy
    private lateinit var testableLooper: TestableLooper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        testableLooper = TestableLooper.get(this)
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.string.status_bar_alarm_clock,
            ALARM_SLOT
        )
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.string.status_bar_managed_profile,
            MANAGED_PROFILE_SLOT
        )
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyManagerResources)
        whenever(devicePolicyManagerResources.getString(anyString(), any())).thenReturn("")
        statusBarPolicy = createStatusBarPolicy()
    }

    @Test
    fun testDeviceNotProvisioned_alarmIconNotShown() {
        val alarmInfo = createAlarmInfo()

        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)
        statusBarPolicy.init()
        verify(nextAlarmController).addCallback(capture(alarmCallbackCaptor))

        whenever(alarmManager.getNextAlarmClock(anyInt())).thenReturn(alarmInfo)

        alarmCallbackCaptor.value.onNextAlarmChanged(alarmInfo)
        verify(iconController, never()).setIconVisibility(ALARM_SLOT, true)
    }

    @Test
    fun testDeviceProvisioned_alarmIconShown() {
        val alarmInfo = createAlarmInfo()

        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
        statusBarPolicy.init()

        verify(nextAlarmController).addCallback(capture(alarmCallbackCaptor))
        whenever(alarmManager.getNextAlarmClock(anyInt())).thenReturn(alarmInfo)

        alarmCallbackCaptor.value.onNextAlarmChanged(alarmInfo)
        verify(iconController).setIconVisibility(ALARM_SLOT, true)
    }

    @Test
    fun testDeviceProvisionedChanged_alarmIconShownAfterCurrentUserSetup() {
        val alarmInfo = createAlarmInfo()

        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)
        statusBarPolicy.init()

        verify(nextAlarmController).addCallback(capture(alarmCallbackCaptor))
        whenever(alarmManager.getNextAlarmClock(anyInt())).thenReturn(alarmInfo)

        alarmCallbackCaptor.value.onNextAlarmChanged(alarmInfo)
        verify(iconController, never()).setIconVisibility(ALARM_SLOT, true)

        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)
        statusBarPolicy.onUserSetupChanged()
        verify(iconController).setIconVisibility(ALARM_SLOT, true)
    }

    @Test
    fun testAppTransitionFinished_doesNotShowManagedProfileIcon() {
        whenever(userManager.getUserStatusBarIconResId(anyInt())).thenReturn(0 /* ID_NULL */)
        whenever(keyguardStateController.isShowing).thenReturn(false)
        statusBarPolicy.appTransitionFinished(0)
        // The above call posts to bgExecutor and then back to mainExecutor
        executor.advanceClockToLast()
        executor.runAllReady()
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(iconController, never()).setIconVisibility(MANAGED_PROFILE_SLOT, true)
    }

    @Test
    fun testAppTransitionFinished_showsManagedProfileIcon() {
        whenever(userManager.getUserStatusBarIconResId(anyInt())).thenReturn(100)
        whenever(keyguardStateController.isShowing).thenReturn(false)
        statusBarPolicy.appTransitionFinished(0)
        // The above call posts to bgExecutor and then back to mainExecutor
        executor.advanceClockToLast()
        executor.runAllReady()
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(iconController).setIconVisibility(MANAGED_PROFILE_SLOT, true)
    }

    @Test
    fun connectedDisplay_connected_iconShown() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.emit(State.CONNECTED)
            runCurrent()

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
        }

    @Test
    fun connectedDisplay_disconnected_iconHidden() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.emit(State.DISCONNECTED)

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, false)
        }

    @Test
    fun connectedDisplay_disconnectedThenConnected_iconShown() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.emit(State.CONNECTED)
            fakeConnectedDisplayStateProvider.emit(State.DISCONNECTED)
            fakeConnectedDisplayStateProvider.emit(State.CONNECTED)

            inOrder(iconController).apply {
                verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
                verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, false)
                verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
            }
        }

    @Test
    fun connectedDisplay_connectSecureDisplay_iconShown() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.emit(State.CONNECTED_SECURE)

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
        }

    private fun createAlarmInfo(): AlarmManager.AlarmClockInfo {
        return AlarmManager.AlarmClockInfo(10L, null)
    }

    private fun createStatusBarPolicy(): PhoneStatusBarPolicy {
        return PhoneStatusBarPolicy(
            iconController,
            commandQueue,
            broadcastDispatcher,
            executor,
            executor,
            testableLooper.looper,
            context.resources,
            castController,
            hotspotController,
            bluetoothController,
            nextAlarmController,
            userInfoController,
            rotationLockController,
            dataSaverController,
            zenModeController,
            deviceProvisionedController,
            keyguardStateController,
            locationController,
            sensorPrivacyController,
            alarmManager,
            userManager,
            userTracker,
            devicePolicyManager,
            recordingController,
            telecomManager,
            /* displayId = */ 0,
            sharedPreferences,
            dateFormatUtil,
            ringerModeTracker,
            privacyItemController,
            privacyLogger,
            fakeConnectedDisplayStateProvider,
            JavaAdapter(testScope.backgroundScope)
        )
    }

    private class FakeConnectedDisplayStateProvider : ConnectedDisplayInteractor {
        private val flow = MutableSharedFlow<State>()
        suspend fun emit(value: State) = flow.emit(value)
        override val connectedDisplayState: Flow<State>
            get() = flow
        override val connectedDisplayAddition: Flow<Unit>
            get() = TODO("Not yet implemented")
        override val pendingDisplay: Flow<PendingDisplay?>
            get() = TODO("Not yet implemented")
        override val concurrentDisplaysInProgress: Flow<Boolean>
            get() = TODO("Not yet implemented")
    }
}
