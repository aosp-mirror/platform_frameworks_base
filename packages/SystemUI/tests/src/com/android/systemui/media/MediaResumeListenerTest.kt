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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.MediaDescription
import android.media.session.MediaSession
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

private const val KEY = "TEST_KEY"
private const val OLD_KEY = "RESUME_KEY"
private const val APP = "APP"
private const val BG_COLOR = Color.RED
private const val PACKAGE_NAME = "PKG"
private const val CLASS_NAME = "CLASS"
private const val ARTIST = "ARTIST"
private const val TITLE = "TITLE"
private const val USER_ID = 0
private const val MEDIA_PREFERENCES = "media_control_prefs"
private const val RESUME_COMPONENTS = "package1/class1:package2/class2:package3/class3"

private fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> any(): T = Mockito.any<T>()

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaResumeListenerTest : SysuiTestCase() {

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var device: MediaDeviceData
    @Mock private lateinit var token: MediaSession.Token
    @Mock private lateinit var tunerService: TunerService
    @Mock private lateinit var resumeBrowserFactory: ResumeMediaBrowserFactory
    @Mock private lateinit var resumeBrowser: ResumeMediaBrowser
    @Mock private lateinit var sharedPrefs: SharedPreferences
    @Mock private lateinit var sharedPrefsEditor: SharedPreferences.Editor
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var pendingIntent: PendingIntent

    @Captor lateinit var callbackCaptor: ArgumentCaptor<ResumeMediaBrowser.Callback>

    private lateinit var executor: FakeExecutor
    private lateinit var data: MediaData
    private lateinit var resumeListener: MediaResumeListener

    private var originalQsSetting = Settings.Global.getInt(context.contentResolver,
        Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1)
    private var originalResumeSetting = Settings.Secure.getInt(context.contentResolver,
        Settings.Secure.MEDIA_CONTROLS_RESUME, 0)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        Settings.Global.putInt(context.contentResolver,
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1)
        Settings.Secure.putInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RESUME, 1)

        whenever(resumeBrowserFactory.create(capture(callbackCaptor), any()))
                .thenReturn(resumeBrowser)

        // resume components are stored in sharedpreferences
        whenever(mockContext.getSharedPreferences(eq(MEDIA_PREFERENCES), anyInt()))
                .thenReturn(sharedPrefs)
        whenever(sharedPrefs.getString(any(), any())).thenReturn(RESUME_COMPONENTS)
        whenever(sharedPrefs.edit()).thenReturn(sharedPrefsEditor)
        whenever(sharedPrefsEditor.putString(any(), any())).thenReturn(sharedPrefsEditor)
        whenever(mockContext.packageManager).thenReturn(context.packageManager)
        whenever(mockContext.contentResolver).thenReturn(context.contentResolver)

        executor = FakeExecutor(FakeSystemClock())
        resumeListener = MediaResumeListener(mockContext, broadcastDispatcher, executor,
                tunerService, resumeBrowserFactory)
        resumeListener.setManager(mediaDataManager)
        mediaDataManager.addListener(resumeListener)

        data = MediaData(
                userId = USER_ID,
                initialized = true,
                backgroundColor = BG_COLOR,
                app = APP,
                appIcon = null,
                artist = ARTIST,
                song = TITLE,
                artwork = null,
                actions = emptyList(),
                actionsToShowInCompact = emptyList(),
                packageName = PACKAGE_NAME,
                token = token,
                clickIntent = null,
                device = device,
                active = true,
                notificationKey = KEY,
                resumeAction = null)
    }

    @After
    fun tearDown() {
        Settings.Global.putInt(context.contentResolver,
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, originalQsSetting)
        Settings.Secure.putInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RESUME, originalResumeSetting)
    }

    @Test
    fun testWhenNoResumption_doesNothing() {
        Settings.Secure.putInt(context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RESUME, 0)

        // When listener is created, we do NOT register a user change listener
        val listener = MediaResumeListener(context, broadcastDispatcher, executor, tunerService,
                resumeBrowserFactory)
        listener.setManager(mediaDataManager)
        verify(broadcastDispatcher, never()).registerReceiver(eq(listener.userChangeReceiver),
            any(), any(), any())

        // When data is loaded, we do NOT execute or update anything
        listener.onMediaDataLoaded(KEY, OLD_KEY, data)
        assertThat(executor.numPending()).isEqualTo(0)
        verify(mediaDataManager, never()).setResumeAction(any(), any())
    }

    @Test
    fun testOnLoad_checksForResume_noService() {
        // When media data is loaded that has not been checked yet, and does not have a MBS
        resumeListener.onMediaDataLoaded(KEY, null, data)

        // Then we report back to the manager
        verify(mediaDataManager).setResumeAction(KEY, null)
    }

    @Test
    fun testOnLoad_checksForResume_hasService() {
        // Set up mocks to successfully find a MBS that returns valid media
        val pm = mock(PackageManager::class.java)
        whenever(mockContext.packageManager).thenReturn(pm)
        val resolveInfo = ResolveInfo()
        val serviceInfo = ServiceInfo()
        serviceInfo.packageName = PACKAGE_NAME
        resolveInfo.serviceInfo = serviceInfo
        resolveInfo.serviceInfo.name = CLASS_NAME
        val resumeInfo = listOf(resolveInfo)
        whenever(pm.queryIntentServices(any(), anyInt())).thenReturn(resumeInfo)

        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.testConnection()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // When media data is loaded that has not been checked yet, and does have a MBS
        val dataCopy = data.copy(resumeAction = null, hasCheckedForResume = false)
        resumeListener.onMediaDataLoaded(KEY, null, dataCopy)

        // Then we test whether the service is valid
        executor.runAllReady()
        verify(resumeBrowser).testConnection()

        // And since it is, we report back to the manager
        verify(mediaDataManager).setResumeAction(eq(KEY), any())

        // But we do not tell it to add new controls
        verify(mediaDataManager, never())
                .addResumptionControls(anyInt(), any(), any(), any(), any(), any(), any())

        // Finally, make sure the resume browser disconnected
        verify(resumeBrowser).disconnect()
    }

    @Test
    fun testOnLoad_doesNotCheckAgain() {
        // When a media data is loaded that has been checked already
        var dataCopy = data.copy(hasCheckedForResume = true)
        resumeListener.onMediaDataLoaded(KEY, null, dataCopy)

        // Then we should not check it again
        verify(resumeBrowser, never()).testConnection()
        verify(mediaDataManager, never()).setResumeAction(KEY, null)
    }

    @Test
    fun testOnUserUnlock_loadsTracks() {
        // Set up mock service to successfully find valid media
        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.token).thenReturn(token)
        whenever(resumeBrowser.appIntent).thenReturn(pendingIntent)
        whenever(resumeBrowser.findRecentMedia()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // Make sure broadcast receiver is registered
        resumeListener.setManager(mediaDataManager)
        verify(broadcastDispatcher).registerReceiver(eq(resumeListener.userChangeReceiver),
                any(), any(), any())

        // When we get an unlock event
        val intent = Intent(Intent.ACTION_USER_UNLOCKED)
        resumeListener.userChangeReceiver.onReceive(context, intent)

        // Then we should attempt to find recent media for each saved component
        verify(resumeBrowser, times(3)).findRecentMedia()

        // Then since the mock service found media, the manager should be informed
        verify(mediaDataManager, times(3)).addResumptionControls(anyInt(),
                any(), any(), any(), any(), any(), eq(PACKAGE_NAME))
    }
}