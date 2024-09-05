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
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResourcesManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.service.notification.SystemZenRules
import android.service.notification.ZenModeConfig
import android.telecom.TelecomManager
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.StatusBarIcon
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.kosmos.testScope
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.CastDevice
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
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset

@RunWith(AndroidJUnit4::class)
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class PhoneStatusBarPolicyTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val zenModeRepository = kosmos.fakeZenModeRepository

    companion object {
        private const val ZEN_SLOT = "zen"
        private const val ALARM_SLOT = "alarm"
        private const val CAST_SLOT = "cast"
        private const val SCREEN_RECORD_SLOT = "screen_record"
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

    private val testScope = kosmos.testScope
    private val fakeConnectedDisplayStateProvider = FakeConnectedDisplayStateProvider()
    private val zenModeController = FakeZenModeController()

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

            fakeConnectedDisplayStateProvider.setState(State.CONNECTED)
            runCurrent()

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
        }

    @Test
    fun connectedDisplay_disconnected_iconHidden() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.setState(State.DISCONNECTED)
            runCurrent()

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, false)
        }

    @Test
    fun connectedDisplay_disconnectedThenConnected_iconShown() =
        testScope.runTest {
            statusBarPolicy.init()
            clearInvocations(iconController)

            fakeConnectedDisplayStateProvider.setState(State.CONNECTED)
            runCurrent()
            fakeConnectedDisplayStateProvider.setState(State.DISCONNECTED)
            runCurrent()
            fakeConnectedDisplayStateProvider.setState(State.CONNECTED)
            runCurrent()

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

            fakeConnectedDisplayStateProvider.setState(State.CONNECTED_SECURE)
            runCurrent()

            verify(iconController).setIconVisibility(CONNECTED_DISPLAY_SLOT, true)
        }

    @Test
    @DisableFlags(Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun cast_chipsFlagOff_iconShown() {
        statusBarPolicy.init()
        clearInvocations(iconController)

        val callbackCaptor = argumentCaptor<CastController.Callback>()
        verify(castController).addCallback(callbackCaptor.capture())

        whenever(castController.castDevices)
            .thenReturn(
                listOf(
                    CastDevice(
                        "id",
                        "name",
                        "description",
                        CastDevice.CastState.Connected,
                        CastDevice.CastOrigin.MediaProjection,
                    )
                )
            )
        callbackCaptor.firstValue.onCastDevicesChanged()

        verify(iconController).setIconVisibility(CAST_SLOT, true)
    }

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun cast_chipsFlagOn_noCallbackRegistered() {
        statusBarPolicy.init()

        verify(castController, never()).addCallback(any())
    }

    @Test
    @DisableFlags(Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun screenRecord_chipsFlagOff_iconShown_forAllStates() {
        statusBarPolicy.init()
        clearInvocations(iconController)

        val callbackCaptor = argumentCaptor<RecordingController.RecordingStateChangeCallback>()
        verify(recordingController).addCallback(callbackCaptor.capture())

        callbackCaptor.firstValue.onCountdown(3000)
        testableLooper.processAllMessages()
        verify(iconController).setIconVisibility(SCREEN_RECORD_SLOT, true)
        clearInvocations(iconController)

        callbackCaptor.firstValue.onCountdownEnd()
        testableLooper.processAllMessages()
        verify(iconController).setIconVisibility(SCREEN_RECORD_SLOT, false)
        clearInvocations(iconController)

        callbackCaptor.firstValue.onRecordingStart()
        testableLooper.processAllMessages()
        verify(iconController).setIconVisibility(SCREEN_RECORD_SLOT, true)
        clearInvocations(iconController)

        callbackCaptor.firstValue.onRecordingEnd()
        testableLooper.processAllMessages()
        verify(iconController).setIconVisibility(SCREEN_RECORD_SLOT, false)
        clearInvocations(iconController)
    }

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun screenRecord_chipsFlagOn_noCallbackRegistered() {
        statusBarPolicy.init()

        verify(recordingController, never()).addCallback(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_STATUS_BAR_SCREEN_SHARING_CHIPS)
    fun screenRecord_chipsFlagOn_methodsDoNothing() {
        statusBarPolicy.init()
        clearInvocations(iconController)

        statusBarPolicy.onCountdown(3000)
        testableLooper.processAllMessages()
        verify(iconController, never()).setIconVisibility(eq(SCREEN_RECORD_SLOT), any())

        statusBarPolicy.onCountdownEnd()
        testableLooper.processAllMessages()
        verify(iconController, never()).setIconVisibility(eq(SCREEN_RECORD_SLOT), any())

        statusBarPolicy.onRecordingStart()
        testableLooper.processAllMessages()
        verify(iconController, never()).setIconVisibility(eq(SCREEN_RECORD_SLOT), any())

        statusBarPolicy.onRecordingEnd()
        testableLooper.processAllMessages()
        verify(iconController, never()).setIconVisibility(eq(SCREEN_RECORD_SLOT), any())
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_UI, android.app.Flags.FLAG_MODES_UI_ICONS)
    fun zenModeInteractorActiveModeChanged_showsModeIcon() =
        testScope.runTest {
            statusBarPolicy.init()
            reset(iconController)

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId("bedtime")
                        .setName("Bedtime Mode")
                        .setType(AutomaticZenRule.TYPE_BEDTIME)
                        .setActive(true)
                        .setPackage(mContext.packageName)
                        .setIconResId(android.R.drawable.ic_lock_lock)
                        .build(),
                    TestModeBuilder()
                        .setId("other")
                        .setName("Other Mode")
                        .setType(AutomaticZenRule.TYPE_OTHER)
                        .setActive(true)
                        .setPackage(SystemZenRules.PACKAGE_ANDROID)
                        .setIconResId(android.R.drawable.ic_media_play)
                        .build(),
                )
            )
            runCurrent()

            verify(iconController).setIconVisibility(eq(ZEN_SLOT), eq(true))
            verify(iconController)
                .setResourceIcon(
                    eq(ZEN_SLOT),
                    eq(mContext.packageName),
                    eq(android.R.drawable.ic_lock_lock),
                    any(), // non-null
                    eq("Bedtime Mode"),
                    eq(StatusBarIcon.Shape.FIXED_SPACE)
                )

            zenModeRepository.deactivateMode("bedtime")
            runCurrent()

            verify(iconController)
                .setResourceIcon(
                    eq(ZEN_SLOT),
                    eq(null),
                    eq(android.R.drawable.ic_media_play),
                    any(), // non-null
                    eq("Other Mode"),
                    eq(StatusBarIcon.Shape.FIXED_SPACE)
                )

            zenModeRepository.deactivateMode("other")
            runCurrent()

            verify(iconController).setIconVisibility(eq(ZEN_SLOT), eq(false))
        }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_UI, android.app.Flags.FLAG_MODES_UI_ICONS)
    fun zenModeControllerOnGlobalZenChanged_doesNotUpdateDndIcon() {
        statusBarPolicy.init()
        reset(iconController)

        zenModeController.setZen(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null)

        verify(iconController, never()).setIconVisibility(eq(ZEN_SLOT), any())
        verify(iconController, never()).setIcon(eq(ZEN_SLOT), anyInt(), any())
        verify(iconController, never())
            .setResourceIcon(eq(ZEN_SLOT), any(), any(), any(), any(), any())
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_MODES_UI_ICONS)
    fun zenModeInteractorActiveModeChanged_withFlagDisabled_ignored() =
        testScope.runTest {
            statusBarPolicy.init()
            reset(iconController)

            zenModeRepository.addMode(id = "Bedtime", active = true)
            runCurrent()

            verify(iconController, never()).setIconVisibility(eq(ZEN_SLOT), any())
            verify(iconController, never()).setIcon(eq(ZEN_SLOT), anyInt(), any())
            verify(iconController, never())
                .setResourceIcon(eq(ZEN_SLOT), any(), any(), any(), any(), any())
        }

    @Test
    @DisableFlags(android.app.Flags.FLAG_MODES_UI_ICONS)
    fun zenModeControllerOnGlobalZenChanged_withFlagDisabled_updatesDndIcon() {
        statusBarPolicy.init()
        reset(iconController)

        zenModeController.setZen(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null)

        verify(iconController).setIconVisibility(eq(ZEN_SLOT), eq(true))
        verify(iconController).setIcon(eq(ZEN_SLOT), anyInt(), eq("Priority only"))

        zenModeController.setZen(Settings.Global.ZEN_MODE_OFF, null, null)

        verify(iconController).setIconVisibility(eq(ZEN_SLOT), eq(false))
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
            kosmos.zenModeInteractor,
            JavaAdapter(testScope.backgroundScope)
        )
    }

    private class FakeConnectedDisplayStateProvider : ConnectedDisplayInteractor {
        private val flow = MutableStateFlow(State.DISCONNECTED)

        fun setState(value: State) {
            flow.value = value
        }

        override val connectedDisplayState: Flow<State>
            get() = flow

        override val connectedDisplayAddition: Flow<Unit>
            get() = TODO("Not yet implemented")

        override val pendingDisplay: Flow<PendingDisplay?>
            get() = TODO("Not yet implemented")

        override val concurrentDisplaysInProgress: Flow<Boolean>
            get() = TODO("Not yet implemented")
    }

    private class FakeZenModeController : ZenModeController {

        private val callbacks = mutableListOf<ZenModeController.Callback>()
        private var zen = Settings.Global.ZEN_MODE_OFF
        private var consolidatedPolicy = NotificationManager.Policy(0, 0, 0)

        override fun addCallback(listener: ZenModeController.Callback) {
            callbacks.add(listener)
        }

        override fun removeCallback(listener: ZenModeController.Callback) {
            callbacks.remove(listener)
        }

        override fun setZen(zen: Int, conditionId: Uri?, reason: String?) {
            this.zen = zen
            callbacks.forEach { it.onZenChanged(zen) }
        }

        override fun getZen(): Int = zen

        override fun getManualRule(): ZenModeConfig.ZenRule = throw NotImplementedError()

        override fun getConfig(): ZenModeConfig = throw NotImplementedError()

        fun setConsolidatedPolicy(policy: NotificationManager.Policy) {
            this.consolidatedPolicy = policy
            callbacks.forEach { it.onConsolidatedPolicyChanged(consolidatedPolicy) }
        }

        override fun getConsolidatedPolicy(): NotificationManager.Policy = consolidatedPolicy

        override fun getNextAlarm() = throw NotImplementedError()

        override fun isZenAvailable() = throw NotImplementedError()

        override fun getEffectsSuppressor() = throw NotImplementedError()

        override fun isCountdownConditionSupported() = throw NotImplementedError()

        override fun getCurrentUser() = throw NotImplementedError()

        override fun isVolumeRestricted() = throw NotImplementedError()

        override fun areNotificationsHiddenInShade() = throw NotImplementedError()
    }
}
