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

package com.android.systemui.media.muteawait

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioAttributes.USAGE_UNKNOWN
import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.MuteAwaitConnectionCallback.EVENT_CONNECTION
import androidx.test.filters.SmallTest
import com.android.settingslib.media.DeviceIconUtil
import com.android.settingslib.media.LocalMediaManager
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations


@SmallTest
class MediaMuteAwaitConnectionManagerTest : SysuiTestCase() {
    private lateinit var muteAwaitConnectionManager: MediaMuteAwaitConnectionManager
    @Mock
    private lateinit var audioManager: AudioManager
    @Mock
    private lateinit var deviceIconUtil: DeviceIconUtil
    @Mock
    private lateinit var localMediaManager: LocalMediaManager
    @Mock
    private lateinit var logger: MediaMuteAwaitLogger
    private lateinit var icon: Drawable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.addMockSystemService(Context.AUDIO_SERVICE, audioManager)
        icon = context.getDrawable(R.drawable.ic_cake)!!
        whenever(deviceIconUtil.getIconFromAudioDeviceType(any(), any())).thenReturn(icon)

        muteAwaitConnectionManager = MediaMuteAwaitConnectionManager(
            FakeExecutor(FakeSystemClock()),
            localMediaManager,
            context,
            deviceIconUtil,
            logger
        )
    }

    @Test
    fun constructor_audioManagerCallbackNotRegistered() {
        verify(audioManager, never()).registerMuteAwaitConnectionCallback(any(), any())
    }

    @Test
    fun startListening_audioManagerCallbackRegistered() {
        muteAwaitConnectionManager.startListening()

        verify(audioManager).registerMuteAwaitConnectionCallback(any(), any())
    }

    @Test
    fun stopListening_audioManagerCallbackUnregistered() {
        muteAwaitConnectionManager.stopListening()

        verify(audioManager).unregisterMuteAwaitConnectionCallback(any())
    }

    @Test
    fun startListening_audioManagerHasNoMuteAwaitDevice_localMediaMangerNotNotified() {
        whenever(audioManager.mutingExpectedDevice).thenReturn(null)

        muteAwaitConnectionManager.startListening()

        verify(localMediaManager, never()).dispatchAboutToConnectDeviceAdded(any(), any(), any())
    }

    @Test
    fun startListening_audioManagerHasMuteAwaitDevice_localMediaMangerNotified() {
        whenever(audioManager.mutingExpectedDevice).thenReturn(DEVICE)

        muteAwaitConnectionManager.startListening()

        verify(localMediaManager).dispatchAboutToConnectDeviceAdded(
            eq(DEVICE_ADDRESS), eq(DEVICE_NAME), eq(icon)
        )
    }

    @Test
    fun onMutedUntilConnection_notUsageMedia_localMediaManagerNotNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()

        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_UNKNOWN))

        verify(localMediaManager, never()).dispatchAboutToConnectDeviceAdded(any(), any(), any())
    }

    @Test
    fun onMutedUntilConnection_isUsageMedia_localMediaManagerNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()


        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))

        verify(localMediaManager).dispatchAboutToConnectDeviceAdded(
            eq(DEVICE_ADDRESS), eq(DEVICE_NAME), eq(icon)
        )
    }

    @Test
    fun onUnmutedEvent_noDeviceMutedBefore_localMediaManagerNotNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()

        muteAwaitListener.onUnmutedEvent(EVENT_CONNECTION, DEVICE, intArrayOf(USAGE_MEDIA))

        verify(localMediaManager, never()).dispatchAboutToConnectDeviceAdded(any(), any(), any())
    }

    @Test
    fun onUnmutedEvent_notSameDevice_localMediaManagerNotNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()
        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))
        reset(localMediaManager)

        val otherDevice = AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                "address",
                "DifferentName",
                listOf(),
                listOf(),
        )
        muteAwaitListener.onUnmutedEvent(EVENT_CONNECTION, otherDevice, intArrayOf(USAGE_MEDIA))

        verify(localMediaManager, never()).dispatchAboutToConnectDeviceAdded(any(), any(), any())
    }

    @Test
    fun onUnmutedEvent_notUsageMedia_localMediaManagerNotNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()
        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))
        reset(localMediaManager)

        muteAwaitListener.onUnmutedEvent(EVENT_CONNECTION, DEVICE, intArrayOf(USAGE_UNKNOWN))

        verify(localMediaManager, never()).dispatchAboutToConnectDeviceAdded(any(), any(), any())
    }

    @Test
    fun onUnmutedEvent_sameDeviceAndUsageMedia_localMediaManagerNotified() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()
        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))
        reset(localMediaManager)

        muteAwaitListener.onUnmutedEvent(EVENT_CONNECTION, DEVICE, intArrayOf(USAGE_MEDIA))

        verify(localMediaManager).dispatchAboutToConnectDeviceRemoved()
    }

    @Test
    fun onMutedUntilConnection_isLogged() {
        muteAwaitConnectionManager.startListening()

        getMuteAwaitListener().onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))

        verify(logger).logMutedDeviceAdded(DEVICE_ADDRESS, DEVICE_NAME, hasMediaUsage = true)
    }

    @Test
    fun onUnmutedEvent_notMostRecentDevice_isLogged() {
        muteAwaitConnectionManager.startListening()

        getMuteAwaitListener().onUnmutedEvent(EVENT_CONNECTION, DEVICE, intArrayOf(USAGE_MEDIA))

        verify(logger).logMutedDeviceRemoved(
            DEVICE_ADDRESS, DEVICE_NAME, hasMediaUsage = true, isMostRecentDevice = false
        )
    }

    @Test
    fun onUnmutedEvent_isMostRecentDevice_isLogged() {
        muteAwaitConnectionManager.startListening()
        val muteAwaitListener = getMuteAwaitListener()

        muteAwaitListener.onMutedUntilConnection(DEVICE, intArrayOf(USAGE_MEDIA))
        muteAwaitListener.onUnmutedEvent(EVENT_CONNECTION, DEVICE, intArrayOf(USAGE_MEDIA))

        verify(logger).logMutedDeviceRemoved(
            DEVICE_ADDRESS, DEVICE_NAME, hasMediaUsage = true, isMostRecentDevice = true
        )
    }

    private fun getMuteAwaitListener(): AudioManager.MuteAwaitConnectionCallback {
        val listenerCaptor = ArgumentCaptor.forClass(
                AudioManager.MuteAwaitConnectionCallback::class.java
        )
        verify(audioManager).registerMuteAwaitConnectionCallback(any(), listenerCaptor.capture())
        return listenerCaptor.value!!
    }
}

private const val DEVICE_ADDRESS = "DeviceAddress"
private const val DEVICE_NAME = "DeviceName"
private val DEVICE = AudioDeviceAttributes(
        AudioDeviceAttributes.ROLE_OUTPUT,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        DEVICE_ADDRESS,
        DEVICE_NAME,
        listOf(),
        listOf(),
)
