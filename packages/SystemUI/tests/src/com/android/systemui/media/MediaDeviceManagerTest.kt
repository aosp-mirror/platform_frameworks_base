/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Process
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest

import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock

import com.google.common.truth.Truth.assertThat

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

private const val KEY = "TEST_KEY"
private const val PACKAGE = "PKG"
private const val SESSION_KEY = "SESSION_KEY"
private const val SESSION_ARTIST = "SESSION_ARTIST"
private const val SESSION_TITLE = "SESSION_TITLE"
private const val DEVICE_NAME = "DEVICE_NAME"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class MediaDeviceManagerTest : SysuiTestCase() {

    private lateinit var manager: MediaDeviceManager

    @Mock private lateinit var lmmFactory: LocalMediaManagerFactory
    @Mock private lateinit var lmm: LocalMediaManager
    @Mock private lateinit var featureFlag: MediaFeatureFlag
    private lateinit var fakeExecutor: FakeExecutor

    @Mock private lateinit var device: MediaDevice
    private lateinit var session: MediaSession
    private lateinit var metadataBuilder: MediaMetadata.Builder
    private lateinit var playbackBuilder: PlaybackState.Builder
    private lateinit var notifBuilder: Notification.Builder
    private lateinit var sbn: StatusBarNotification

    @Before
    fun setup() {
        lmmFactory = mock(LocalMediaManagerFactory::class.java)
        lmm = mock(LocalMediaManager::class.java)
        device = mock(MediaDevice::class.java)
        whenever(device.name).thenReturn(DEVICE_NAME)
        whenever(lmmFactory.create(PACKAGE)).thenReturn(lmm)
        whenever(lmm.getCurrentConnectedDevice()).thenReturn(device)
        featureFlag = mock(MediaFeatureFlag::class.java)
        whenever(featureFlag.enabled).thenReturn(true)

        fakeExecutor = FakeExecutor(FakeSystemClock())

        manager = MediaDeviceManager(context, lmmFactory, featureFlag, fakeExecutor)

        // Create a media sesssion and notification for testing.
        metadataBuilder = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_ARTIST, SESSION_ARTIST)
            putString(MediaMetadata.METADATA_KEY_TITLE, SESSION_TITLE)
        }
        playbackBuilder = PlaybackState.Builder().apply {
            setState(PlaybackState.STATE_PAUSED, 6000L, 1f)
            setActions(PlaybackState.ACTION_PLAY)
        }
        session = MediaSession(context, SESSION_KEY).apply {
            setMetadata(metadataBuilder.build())
            setPlaybackState(playbackBuilder.build())
        }
        session.setActive(true)
        notifBuilder = Notification.Builder(context, "NONE").apply {
            setContentTitle(SESSION_TITLE)
            setContentText(SESSION_ARTIST)
            setSmallIcon(android.R.drawable.ic_media_pause)
            setStyle(Notification.MediaStyle().setMediaSession(session.getSessionToken()))
        }
        sbn = StatusBarNotification(PACKAGE, PACKAGE, 0, "TAG", Process.myUid(), 0, 0,
                notifBuilder.build(), Process.myUserHandle(), 0)
    }

    @After
    fun tearDown() {
        session.release()
    }

    @Test
    fun removeUnknown() {
        manager.onNotificationRemoved("unknown")
    }

    @Test
    fun addNotification() {
        manager.onNotificationAdded(KEY, sbn)
        verify(lmmFactory).create(PACKAGE)
    }

    @Test
    fun featureDisabled() {
        whenever(featureFlag.enabled).thenReturn(false)
        manager.onNotificationAdded(KEY, sbn)
        verify(lmmFactory, never()).create(PACKAGE)
    }

    @Test
    fun addAndRemoveNotification() {
        manager.onNotificationAdded(KEY, sbn)
        manager.onNotificationRemoved(KEY)
        verify(lmm).unregisterCallback(any())
    }

    @Test
    fun deviceListUpdate() {
        val listener = mock(MediaDeviceManager.Listener::class.java)
        manager.addListener(listener)
        manager.onNotificationAdded(KEY, sbn)
        val deviceCallback = captureCallback()
        // WHEN the device list changes
        deviceCallback.onDeviceListUpdate(mutableListOf(device))
        assertThat(fakeExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val captor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener).onMediaDeviceChanged(eq(KEY), captor.capture())
        val data = captor.getValue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun selectedDeviceStateChanged() {
        val listener = mock(MediaDeviceManager.Listener::class.java)
        manager.addListener(listener)
        manager.onNotificationAdded(KEY, sbn)
        val deviceCallback = captureCallback()
        // WHEN the selected device changes state
        deviceCallback.onSelectedDeviceStateChanged(device, 1)
        assertThat(fakeExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val captor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener).onMediaDeviceChanged(eq(KEY), captor.capture())
        val data = captor.getValue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun listenerReceivesKeyRemoved() {
        manager.onNotificationAdded(KEY, sbn)
        val listener = mock(MediaDeviceManager.Listener::class.java)
        manager.addListener(listener)
        // WHEN the notification is removed
        manager.onNotificationRemoved(KEY)
        // THEN the listener receives key removed event
        verify(listener).onKeyRemoved(eq(KEY))
    }

    fun captureCallback(): LocalMediaManager.DeviceCallback {
        val captor = ArgumentCaptor.forClass(LocalMediaManager.DeviceCallback::class.java)
        verify(lmm).registerCallback(captor.capture())
        return captor.getValue()
    }
}
