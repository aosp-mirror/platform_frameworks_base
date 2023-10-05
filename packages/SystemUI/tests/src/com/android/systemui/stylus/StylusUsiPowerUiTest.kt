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

import android.app.ActivityManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.core.app.NotificationManagerCompat
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
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

    @Mock lateinit var uiEventLogger: UiEventLogger
    @Captor lateinit var notificationCaptor: ArgumentCaptor<Notification>

    private lateinit var stylusUsiPowerUi: StylusUsiPowerUI
    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var contextSpy: Context

    private val instanceIdSequenceFake = InstanceIdSequenceFake(10)

    private val uid = ActivityManager.getCurrentUser()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        contextSpy = spy(mContext)
        doNothing().whenever(contextSpy).startActivity(any())

        whenever(handler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf())
        whenever(inputManager.getInputDevice(0)).thenReturn(btStylusDevice)
        whenever(btStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(btStylusDevice.bluetoothAddress).thenReturn("SO:ME:AD:DR:ES")

        stylusUsiPowerUi =
            StylusUsiPowerUI(contextSpy, notificationManager, inputManager, handler, uiEventLogger)
        stylusUsiPowerUi.instanceIdSequence = instanceIdSequenceFake

        broadcastReceiver = stylusUsiPowerUi.receiver
    }

    @Test
    fun updateBatteryState_capacityZero_noop() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0f))

        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_capacityBelowThreshold_notifies() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))

        verify(notificationManager, times(1))
            .notify(eq(R.string.stylus_battery_low_percentage), any())
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_capacityAboveThreshold_cancelsNotificattion() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.8f))

        verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low_percentage)
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_existingNotification_capacityAboveThreshold_cancelsNotification() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.8f))

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1))
                .notify(eq(R.string.stylus_battery_low_percentage), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low_percentage)
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    fun updateBatteryState_existingNotification_capacityBelowThreshold_updatesNotification() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.15f))

        verify(notificationManager, times(2))
            .notify(eq(R.string.stylus_battery_low_percentage), notificationCaptor.capture())
        assertEquals(
            notificationCaptor.value.extras.getString(Notification.EXTRA_TITLE),
            context.getString(R.string.stylus_battery_low_percentage, "15%")
        )
        assertEquals(
            notificationCaptor.value.extras.getString(Notification.EXTRA_TEXT),
            context.getString(R.string.stylus_battery_low_subtitle)
        )
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_capacityAboveThenBelowThreshold_hidesThenShowsNotification() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.5f))
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1))
                .notify(eq(R.string.stylus_battery_low_percentage), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low_percentage)
            it.verify(notificationManager, times(1))
                .notify(eq(R.string.stylus_battery_low_percentage), any())
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    fun updateSuppression_noExistingNotification_cancelsNotification() {
        stylusUsiPowerUi.updateSuppression(true)

        verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low_percentage)
        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateSuppression_existingNotification_cancelsNotification() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))

        stylusUsiPowerUi.updateSuppression(true)

        inOrder(notificationManager).let {
            it.verify(notificationManager, times(1))
                .notify(eq(R.string.stylus_battery_low_percentage), any())
            it.verify(notificationManager, times(1)).cancel(R.string.stylus_battery_low_percentage)
            it.verifyNoMoreInteractions()
        }
    }

    @Test
    fun refresh_hasConnectedBluetoothStylus_existingNotification_doesNothing() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(0))
        clearInvocations(notificationManager)

        stylusUsiPowerUi.refresh()

        verifyNoMoreInteractions(notificationManager)
    }

    @Test
    fun updateBatteryState_showsNotification_logsNotificationShown() {
        stylusUsiPowerUi.updateBatteryState(0, FixedCapacityBatteryState(0.1f))

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.STYLUS_LOW_BATTERY_NOTIFICATION_SHOWN,
                uid,
                contextSpy.packageName,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId),
                10
            )
    }

    @Test
    fun broadcastReceiver_clicked_hasInputDeviceId_startsUsiDetailsActivity() {
        val intent = Intent(StylusUsiPowerUI.ACTION_CLICKED_LOW_BATTERY)
        val activityIntentCaptor = argumentCaptor<Intent>()
        stylusUsiPowerUi.updateBatteryState(1, FixedCapacityBatteryState(0.15f))
        broadcastReceiver.onReceive(contextSpy, intent)

        verify(contextSpy, times(1)).startActivity(activityIntentCaptor.capture())
        assertThat(activityIntentCaptor.value.action)
            .isEqualTo(StylusUsiPowerUI.ACTION_STYLUS_USI_DETAILS)
        val args =
            activityIntentCaptor.value.getExtra(StylusUsiPowerUI.KEY_SETTINGS_FRAGMENT_ARGS)
                as Bundle
        assertThat(args.getInt(StylusUsiPowerUI.KEY_DEVICE_INPUT_ID)).isEqualTo(1)
    }

    @Test
    fun broadcastReceiver_clicked_nullInputDeviceId_doesNotStartActivity() {
        val intent = Intent(StylusUsiPowerUI.ACTION_CLICKED_LOW_BATTERY)
        broadcastReceiver.onReceive(contextSpy, intent)

        verify(contextSpy, never()).startActivity(any())
    }

    @Test
    fun broadcastReceiver_clicked_logsNotificationClicked() {
        val intent = Intent(StylusUsiPowerUI.ACTION_CLICKED_LOW_BATTERY)
        broadcastReceiver.onReceive(contextSpy, intent)

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.STYLUS_LOW_BATTERY_NOTIFICATION_CLICKED,
                uid,
                contextSpy.packageName,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId),
                100
            )
    }

    @Test
    fun broadcastReceiver_dismissed_logsNotificationDismissed() {
        val intent = Intent(StylusUsiPowerUI.ACTION_DISMISSED_LOW_BATTERY)
        broadcastReceiver.onReceive(contextSpy, intent)

        verify(uiEventLogger, times(1))
            .logWithInstanceIdAndPosition(
                StylusUiEvent.STYLUS_LOW_BATTERY_NOTIFICATION_DISMISSED,
                uid,
                contextSpy.packageName,
                InstanceId.fakeInstanceId(instanceIdSequenceFake.lastInstanceId),
                100
            )
    }
}
