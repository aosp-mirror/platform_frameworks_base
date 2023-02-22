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

import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.core.app.NotificationManagerCompat
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class StylusUsiPowerUiTest : SysuiTestCase() {
    @Mock lateinit var notificationManager: NotificationManagerCompat
    @Mock lateinit var inputManager: InputManager
    @Mock lateinit var handler: Handler
    @Mock lateinit var btStylusDevice: InputDevice

    private lateinit var stylusUsiPowerUi: StylusUsiPowerUI

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(handler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf())
        whenever(inputManager.getInputDevice(0)).thenReturn(btStylusDevice)
        whenever(btStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        // whenever(btStylusDevice.bluetoothAddress).thenReturn("SO:ME:AD:DR:ES")

        stylusUsiPowerUi = StylusUsiPowerUI(mContext, notificationManager, inputManager, handler)
    }

    @Test
    fun updateBatteryState_capacityBelowThreshold_notifies() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))

        verify(notificationManager, times(1)).notify(eq(R.string.stylus_battery_low), any())
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_capacityAboveThreshold_cancelsNotificattion() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.8f))

        verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low)
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_existingNotification_capacityAboveThreshold_cancelsNotification() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.8f))

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1)).notify(eq(R.string.stylus_battery_low), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low)
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    fun updateBatteryState_existingNotification_capacityBelowThreshold_updatesNotification() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.15f))

        verify(notificationManager, times(2)).notify(eq(R.string.stylus_battery_low), any())
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_capacityAboveThenBelowThreshold_hidesThenShowsNotification() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.5f))
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1)).notify(eq(R.string.stylus_battery_low), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low)
            it.verify(notificationManager, times(1)).notify(eq(R.string.stylus_battery_low), any())
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    fun updateSuppression_noExistingNotification_cancelsNotification() {
        stylusUsiPowerUi.updateSuppression(true)

        verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low)
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateSuppression_existingNotification_cancelsNotification() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))

        stylusUsiPowerUi.updateSuppression(true)

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1)).notify(eq(R.string.stylus_battery_low), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low)
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    @Ignore("TODO(b/257936830): get bt address once input api available")
    fun refresh_hasConnectedBluetoothStylus_doesNotNotify() {
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(0))

        stylusUsiPowerUi.refresh()

        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    @Ignore("TODO(b/257936830): get bt address once input api available")
    fun refresh_hasConnectedBluetoothStylus_existingNotification_cancelsNotification() {
        stylusUsiPowerUi.updateBatteryState(FixedCapacityBatteryState(0.1f))
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(0))

        stylusUsiPowerUi.refresh()

        verify(notificationManager).cancel(R.string.stylus_battery_low)
    }

    class FixedCapacityBatteryState(private val capacity: Float) : BatteryState() {
        override fun getCapacity() = capacity
        override fun getStatus() = 0
        override fun isPresent() = true
    }
}
