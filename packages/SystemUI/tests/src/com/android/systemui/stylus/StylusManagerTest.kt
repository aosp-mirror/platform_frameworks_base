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
package com.android.systemui.stylus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.hardware.input.InputSettings
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.ExtendedMockito.times
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.dx.mockito.inline.extended.MockedVoidMethod
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.quality.Strictness

@RunWith(AndroidTestingRunner::class)
@SmallTest
class StylusManagerTest : SysuiTestCase() {
    @Mock lateinit var inputManager: InputManager
    @Mock lateinit var stylusDevice: InputDevice
    @Mock lateinit var btStylusDevice: InputDevice
    @Mock lateinit var otherDevice: InputDevice
    @Mock lateinit var batteryState: BatteryState
    @Mock lateinit var bluetoothAdapter: BluetoothAdapter
    @Mock lateinit var bluetoothDevice: BluetoothDevice
    @Mock lateinit var handler: Handler
    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var uiEventLogger: UiEventLogger
    @Mock lateinit var stylusCallback: StylusManager.StylusCallback
    @Mock lateinit var otherStylusCallback: StylusManager.StylusCallback

    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var stylusManager: StylusManager
    private val instanceIdSequenceFake = InstanceIdSequenceFake(10)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mockitoSession =
            mockitoSession()
                .mockStatic(InputSettings::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()

        whenever(handler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )

        stylusManager.instanceIdSequence = instanceIdSequenceFake

        whenever(otherDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(false)
        whenever(stylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(btStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)

        whenever(btStylusDevice.isExternal).thenReturn(true)

        whenever(stylusDevice.bluetoothAddress).thenReturn(null)
        whenever(btStylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)

        whenever(btStylusDevice.batteryState).thenReturn(batteryState)
        whenever(stylusDevice.batteryState).thenReturn(batteryState)
        whenever(batteryState.capacity).thenReturn(0.5f)

        whenever(inputManager.getInputDevice(OTHER_DEVICE_ID)).thenReturn(otherDevice)
        whenever(inputManager.getInputDevice(STYLUS_DEVICE_ID)).thenReturn(stylusDevice)
        whenever(inputManager.getInputDevice(BT_STYLUS_DEVICE_ID)).thenReturn(btStylusDevice)
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(STYLUS_DEVICE_ID))

        whenever(bluetoothAdapter.getRemoteDevice(STYLUS_BT_ADDRESS)).thenReturn(bluetoothDevice)
        whenever(bluetoothDevice.address).thenReturn(STYLUS_BT_ADDRESS)

        whenever(featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)).thenReturn(true)

        whenever(InputSettings.isStylusEverUsed(mContext)).thenReturn(false)

        stylusManager.startListener()
        stylusManager.registerCallback(stylusCallback)
        clearInvocations(inputManager)
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun startListener_hasNotStarted_registersInputDeviceListener() {
        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )

        stylusManager.startListener()

        verify(inputManager, times(1)).registerInputDeviceListener(any(), any())
    }

    @Test
    fun startListener_hasNotStarted_registersExistingBluetoothDevice() {
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(BT_STYLUS_DEVICE_ID))

        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )

        stylusManager.startListener()

        verify(bluetoothAdapter, times(1))
            .addOnMetadataChangedListener(bluetoothDevice, EXECUTOR, stylusManager)
    }

    @Test
    fun startListener_hasStarted_doesNothing() {
        stylusManager.startListener()

        verifyNoMoreInteractions(inputManager)
    }

    @Test
    fun onInputDeviceAdded_hasNotStarted_doesNothing() {
        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceAdded_multipleRegisteredCallbacks_callsAll() {
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(stylusCallback)
        verify(otherStylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(otherStylusCallback)
    }

    @Test
    fun onInputDeviceAdded_internalStylus_registersBatteryListener() {
        whenever(stylusDevice.isExternal).thenReturn(false)

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(inputManager, times(1))
            .addInputDeviceBatteryListener(STYLUS_DEVICE_ID, EXECUTOR, stylusManager)
    }

    @Test
    fun onInputDeviceAdded_externalStylus_doesNotRegisterbatteryListener() {
        whenever(stylusDevice.isExternal).thenReturn(true)

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(inputManager, never())
            .addInputDeviceBatteryListener(STYLUS_DEVICE_ID, EXECUTOR, stylusManager)
    }

    @Test
    fun onInputDeviceAdded_stylus_callsCallbacksOnStylusAdded() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceAdded_btStylus_firstUsed_callsCallbacksOnStylusFirstUsed() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusFirstUsed()
    }

    @Test
    fun onInputDeviceAdded_btStylus_firstUsed_setsFlag() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)
        verify(MockedVoidMethod { InputSettings.setStylusEverUsed(mContext, true) }, times(1))
    }

    @Test
    fun onInputDeviceAdded_btStylus_callsCallbacksWithAddress() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        inOrder(stylusCallback).let {
            it.verify(stylusCallback, times(1)).onStylusAdded(BT_STYLUS_DEVICE_ID)
            it.verify(stylusCallback, times(1))
                .onStylusBluetoothConnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
        }
    }

    @Test
    fun onInputDeviceAdded_notStylus_doesNotCallCallbacks() {
        stylusManager.onInputDeviceAdded(OTHER_DEVICE_ID)

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceChanged_hasNotStarted_doesNothing() {
        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceChanged_multipleRegisteredCallbacks_callsAll() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        whenever(stylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
        verify(otherStylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_stylusNewBtConnection_callsCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        whenever(stylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_stylusLostBtConnection_callsCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)
        whenever(btStylusDevice.bluetoothAddress).thenReturn(null)

        stylusManager.onInputDeviceChanged(BT_STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothDisconnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_btConnection_stylusAlreadyBtConnected_onlyCallsListenersOnce() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceChanged(BT_STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_noBtConnection_stylusNeverBtConnected_doesNotCallCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, never()).onStylusBluetoothDisconnected(any(), any())
    }

    @Test
    fun onInputDeviceRemoved_hasNotStarted_doesNothing() {
        stylusManager =
            StylusManager(
                mContext,
                inputManager,
                bluetoothAdapter,
                handler,
                EXECUTOR,
                featureFlags,
                uiEventLogger
            )
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(STYLUS_DEVICE_ID)

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceRemoved_multipleRegisteredCallbacks_callsAll() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceRemoved(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
        verify(otherStylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
    }

    @Test
    fun onInputDeviceRemoved_stylus_callsCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
        verify(stylusCallback, never()).onStylusBluetoothDisconnected(any(), any())
    }

    @Test
    fun onInputDeviceRemoved_unregistersBatteryListener() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(STYLUS_DEVICE_ID)

        verify(inputManager, times(1))
            .removeInputDeviceBatteryListener(STYLUS_DEVICE_ID, stylusManager)
    }

    @Test
    fun onInputDeviceRemoved_btStylus_callsCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(BT_STYLUS_DEVICE_ID)

        inOrder(stylusCallback).let {
            it.verify(stylusCallback, times(1))
                .onStylusBluetoothDisconnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
            it.verify(stylusCallback, times(1)).onStylusRemoved(BT_STYLUS_DEVICE_ID)
        }
    }

    @Test
    fun onStylusBluetoothConnected_registersMetadataListener() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        verify(bluetoothAdapter, times(1)).addOnMetadataChangedListener(any(), any(), any())
    }

    @Test
    fun onStylusBluetoothConnected_noBluetoothDevice_doesNotRegisterMetadataListener() {
        whenever(bluetoothAdapter.getRemoteDevice(STYLUS_BT_ADDRESS)).thenReturn(null)

        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        verify(bluetoothAdapter, never()).addOnMetadataChangedListener(any(), any(), any())
    }

    @Test
    fun onStylusBluetoothConnected_logsEvent() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        verify(uiEventLogger, times(1))
            .logWithInstanceId(
                StylusUiEvent.BLUETOOTH_STYLUS_CONNECTED,
                0,
                null,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId)
            )
    }

    @Test
    fun onStylusBluetoothDisconnected_unregistersMetadataListener() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(BT_STYLUS_DEVICE_ID)

        verify(bluetoothAdapter, times(1)).removeOnMetadataChangedListener(any(), any())
    }

    @Test
    fun onStylusBluetoothDisconnected_logsEventInSameSession() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)
        val instanceId = InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId)

        stylusManager.onInputDeviceRemoved(BT_STYLUS_DEVICE_ID)

        verify(uiEventLogger, times(1))
            .logWithInstanceId(StylusUiEvent.BLUETOOTH_STYLUS_CONNECTED, 0, null, instanceId)
        verify(uiEventLogger, times(1))
            .logWithInstanceId(StylusUiEvent.BLUETOOTH_STYLUS_DISCONNECTED, 0, null, instanceId)
    }

    @Test
    fun onMetadataChanged_chargingStateTrue_executesBatteryCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onMetadataChanged(
            bluetoothDevice,
            BluetoothDevice.METADATA_MAIN_CHARGING,
            "true".toByteArray()
        )

        verify(stylusCallback, times(1))
            .onStylusBluetoothChargingStateChanged(BT_STYLUS_DEVICE_ID, bluetoothDevice, true)
    }

    @Test
    fun onMetadataChanged_chargingStateFalse_executesBatteryCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onMetadataChanged(
            bluetoothDevice,
            BluetoothDevice.METADATA_MAIN_CHARGING,
            "false".toByteArray()
        )

        verify(stylusCallback, times(1))
            .onStylusBluetoothChargingStateChanged(BT_STYLUS_DEVICE_ID, bluetoothDevice, false)
    }

    @Test
    fun onMetadataChanged_chargingStateNoDevice_doesNotExecuteBatteryCallbacks() {
        stylusManager.onMetadataChanged(
            bluetoothDevice,
            BluetoothDevice.METADATA_MAIN_CHARGING,
            "true".toByteArray()
        )

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onMetadataChanged_notChargingState_doesNotExecuteBatteryCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onMetadataChanged(
            bluetoothDevice,
            BluetoothDevice.METADATA_DEVICE_TYPE,
            "true".toByteArray()
        )

        verify(stylusCallback, never()).onStylusBluetoothChargingStateChanged(any(), any(), any())
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_stylusNeverUsed_updateEverUsedFlag() {
        whenever(batteryState.isPresent).thenReturn(true)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(MockedVoidMethod { InputSettings.setStylusEverUsed(mContext, true) }, times(1))
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_stylusNeverUsed_executesStylusFirstUsed() {
        whenever(batteryState.isPresent).thenReturn(true)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(stylusCallback, times(1)).onStylusFirstUsed()
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_notInUsiSession_logsSessionStart() {
        whenever(batteryState.isPresent).thenReturn(true)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_FIRST_DETECTED,
                0,
                null,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId),
                0,
            )
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_btStylusPresent_logsSessionStart() {
        whenever(batteryState.isPresent).thenReturn(true)
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_FIRST_DETECTED,
                0,
                null,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId),
                1,
            )
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_inUsiSession_doesNotLogSessionStart() {
        whenever(batteryState.isPresent).thenReturn(true)
        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)
        clearInvocations(uiEventLogger)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verifyNoMoreInteractions(uiEventLogger)
    }

    @Test
    fun onBatteryStateChanged_batteryAbsent_notInUsiSession_doesNotLogSessionEnd() {
        whenever(batteryState.isPresent).thenReturn(false)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verifyNoMoreInteractions(uiEventLogger)
    }

    @Test
    fun onBatteryStateChanged_batteryAbsent_inUsiSession_logSessionEnd() {
        whenever(batteryState.isPresent).thenReturn(true)
        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)
        val instanceId = InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId)
        whenever(batteryState.isPresent).thenReturn(false)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_FIRST_DETECTED,
                0,
                null,
                instanceId,
                0
            )

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.USI_STYLUS_BATTERY_PRESENCE_REMOVED,
                0,
                null,
                instanceId,
                0
            )
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_stylusUsed_doesNotUpdateEverUsedFlag() {
        whenever(InputSettings.isStylusEverUsed(mContext)).thenReturn(true)

        whenever(batteryState.isPresent).thenReturn(true)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(MockedVoidMethod { InputSettings.setStylusEverUsed(mContext, true) }, never())
    }

    @Test
    fun onBatteryStateChanged_batteryNotPresent_doesNotUpdateEverUsedFlag() {
        whenever(batteryState.isPresent).thenReturn(false)

        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(inputManager, never())
            .removeInputDeviceBatteryListener(STYLUS_DEVICE_ID, stylusManager)
    }

    @Test
    fun onBatteryStateChanged_hasNotStarted_doesNothing() {
        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verifyNoMoreInteractions(inputManager)
    }

    @Test
    fun onBatteryStateChanged_executesBatteryCallbacks() {
        stylusManager.onBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)

        verify(stylusCallback, times(1))
            .onStylusUsiBatteryStateChanged(STYLUS_DEVICE_ID, 1, batteryState)
    }

    companion object {
        private val EXECUTOR = Executor { r -> r.run() }

        private const val OTHER_DEVICE_ID = 0
        private const val STYLUS_DEVICE_ID = 1
        private const val BT_STYLUS_DEVICE_ID = 2

        private const val STYLUS_BT_ADDRESS = "SOME:ADDRESS"
    }
}
