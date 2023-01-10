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

package com.android.systemui.media.controls.resume

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.MediaDescription
import android.media.session.MediaSession
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.player.MediaDeviceData
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.media.controls.pipeline.RESUME_MEDIA_TIMEOUT
import com.android.systemui.settings.UserTracker
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNotNull
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
private const val PACKAGE_NAME = "PKG"
private const val CLASS_NAME = "CLASS"
private const val TITLE = "TITLE"
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
    @Mock private lateinit var userTracker: UserTracker
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
    @Mock private lateinit var dumpManager: DumpManager

    @Captor lateinit var callbackCaptor: ArgumentCaptor<ResumeMediaBrowser.Callback>
    @Captor lateinit var actionCaptor: ArgumentCaptor<Runnable>
    @Captor lateinit var componentCaptor: ArgumentCaptor<String>

    private lateinit var executor: FakeExecutor
    private lateinit var data: MediaData
    private lateinit var resumeListener: MediaResumeListener
    private val clock = FakeSystemClock()

    private var originalQsSetting =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS,
            1
        )
    private var originalResumeSetting =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 0)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        Settings.Global.putInt(
            context.contentResolver,
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS,
            1
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 1)

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
        whenever(mockContext.userId).thenReturn(context.userId)

        executor = FakeExecutor(clock)
        resumeListener =
            MediaResumeListener(
                mockContext,
                broadcastDispatcher,
                userTracker,
                executor,
                executor,
                tunerService,
                resumeBrowserFactory,
                dumpManager,
                clock
            )
        resumeListener.setManager(mediaDataManager)
        mediaDataManager.addListener(resumeListener)

        data =
            MediaTestUtils.emptyMediaData.copy(
                song = TITLE,
                packageName = PACKAGE_NAME,
                token = token
            )
    }

    @After
    fun tearDown() {
        Settings.Global.putInt(
            context.contentResolver,
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS,
            originalQsSetting
        )
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.MEDIA_CONTROLS_RESUME,
            originalResumeSetting
        )
    }

    @Test
    fun testWhenNoResumption_doesNothing() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.MEDIA_CONTROLS_RESUME, 0)

        // When listener is created, we do NOT register a user change listener
        val listener =
            MediaResumeListener(
                context,
                broadcastDispatcher,
                userTracker,
                executor,
                executor,
                tunerService,
                resumeBrowserFactory,
                dumpManager,
                clock
            )
        listener.setManager(mediaDataManager)
        verify(broadcastDispatcher, never())
            .registerReceiver(eq(listener.userUnlockReceiver), any(), any(), any(), anyInt(), any())

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
    fun testOnLoad_checksForResume_badService() {
        setUpMbsWithValidResolveInfo()

        whenever(resumeBrowser.testConnection()).thenAnswer { callbackCaptor.value.onError() }

        // When media data is loaded that has not been checked yet, and does not have a MBS
        resumeListener.onMediaDataLoaded(KEY, null, data)
        executor.runAllReady()

        // Then we report back to the manager
        verify(mediaDataManager).setResumeAction(eq(KEY), eq(null))
    }

    @Test
    fun testOnLoad_localCast_doesNotCheck() {
        // When media data is loaded that has not been checked yet, and is a local cast
        val dataCast = data.copy(playbackLocation = MediaData.PLAYBACK_CAST_LOCAL)
        resumeListener.onMediaDataLoaded(KEY, null, dataCast)

        // Then we do not take action
        verify(mediaDataManager, never()).setResumeAction(any(), any())
    }

    @Test
    fun testOnload_remoteCast_doesNotCheck() {
        // When media data is loaded that has not been checked yet, and is a remote cast
        val dataRcn = data.copy(playbackLocation = MediaData.PLAYBACK_CAST_REMOTE)
        resumeListener.onMediaDataLoaded(KEY, null, dataRcn)

        // Then we do not take action
        verify(mediaDataManager, never()).setResumeAction(any(), any())
    }

    @Test
    fun testOnLoad_checksForResume_hasService() {
        setUpMbsWithValidResolveInfo()

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
        verify(mediaDataManager).setResumeAction(eq(KEY), eq(null))
        verify(resumeBrowser).testConnection()

        // And since it is, we send info to the manager
        verify(mediaDataManager).setResumeAction(eq(KEY), isNotNull())

        // But we do not tell it to add new controls
        verify(mediaDataManager, never())
            .addResumptionControls(anyInt(), any(), any(), any(), any(), any(), any())
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
        verify(broadcastDispatcher)
            .registerReceiver(
                eq(resumeListener.userUnlockReceiver),
                any(),
                any(),
                any(),
                anyInt(),
                any()
            )

        // When we get an unlock event
        val intent = Intent(Intent.ACTION_USER_UNLOCKED)
        intent.putExtra(Intent.EXTRA_USER_HANDLE, context.userId)
        resumeListener.userUnlockReceiver.onReceive(context, intent)

        // Then we should attempt to find recent media for each saved component
        verify(resumeBrowser, times(3)).findRecentMedia()

        // Then since the mock service found media, the manager should be informed
        verify(mediaDataManager, times(3))
            .addResumptionControls(anyInt(), any(), any(), any(), any(), any(), eq(PACKAGE_NAME))
    }

    @Test
    fun testGetResumeAction_restarts() {
        setUpMbsWithValidResolveInfo()

        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.testConnection()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // When media data is loaded that has not been checked yet, and does have a MBS
        val dataCopy = data.copy(resumeAction = null, hasCheckedForResume = false)
        resumeListener.onMediaDataLoaded(KEY, null, dataCopy)

        // Then we test whether the service is valid and set the resume action
        executor.runAllReady()
        verify(mediaDataManager).setResumeAction(eq(KEY), eq(null))
        verify(resumeBrowser).testConnection()
        verify(mediaDataManager, times(2)).setResumeAction(eq(KEY), capture(actionCaptor))

        // When the resume action is run
        actionCaptor.value.run()

        // Then we call restart
        verify(resumeBrowser).restart()
    }

    @Test
    fun testOnUserUnlock_missingTime_saves() {
        val currentTime = clock.currentTimeMillis()

        // When resume components without a last played time are loaded
        testOnUserUnlock_loadsTracks()

        // Then we save an update with the current time
        verify(sharedPrefsEditor).putString(any(), (capture(componentCaptor)))
        componentCaptor.value
            .split(ResumeMediaBrowser.DELIMITER.toRegex())
            .dropLastWhile { it.isEmpty() }
            .forEach {
                val result = it.split("/")
                assertThat(result.size).isEqualTo(3)
                assertThat(result[2].toLong()).isEqualTo(currentTime)
            }
        verify(sharedPrefsEditor, times(1)).apply()
    }

    @Test
    fun testLoadComponents_recentlyPlayed_adds() {
        // Set up browser to return successfully
        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.token).thenReturn(token)
        whenever(resumeBrowser.appIntent).thenReturn(pendingIntent)
        whenever(resumeBrowser.findRecentMedia()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // Set up shared preferences to have a component with a recent lastplayed time
        val lastPlayed = clock.currentTimeMillis()
        val componentsString = "$PACKAGE_NAME/$CLASS_NAME/$lastPlayed:"
        whenever(sharedPrefs.getString(any(), any())).thenReturn(componentsString)
        val resumeListener =
            MediaResumeListener(
                mockContext,
                broadcastDispatcher,
                userTracker,
                executor,
                executor,
                tunerService,
                resumeBrowserFactory,
                dumpManager,
                clock
            )
        resumeListener.setManager(mediaDataManager)
        mediaDataManager.addListener(resumeListener)

        // When we load a component that was played recently
        val intent = Intent(Intent.ACTION_USER_UNLOCKED)
        intent.putExtra(Intent.EXTRA_USER_HANDLE, context.userId)
        resumeListener.userUnlockReceiver.onReceive(mockContext, intent)

        // We add its resume controls
        verify(resumeBrowser, times(1)).findRecentMedia()
        verify(mediaDataManager, times(1))
            .addResumptionControls(anyInt(), any(), any(), any(), any(), any(), eq(PACKAGE_NAME))
    }

    @Test
    fun testLoadComponents_old_ignores() {
        // Set up shared preferences to have a component with an old lastplayed time
        val lastPlayed = clock.currentTimeMillis() - RESUME_MEDIA_TIMEOUT - 100
        val componentsString = "$PACKAGE_NAME/$CLASS_NAME/$lastPlayed:"
        whenever(sharedPrefs.getString(any(), any())).thenReturn(componentsString)
        val resumeListener =
            MediaResumeListener(
                mockContext,
                broadcastDispatcher,
                userTracker,
                executor,
                executor,
                tunerService,
                resumeBrowserFactory,
                dumpManager,
                clock
            )
        resumeListener.setManager(mediaDataManager)
        mediaDataManager.addListener(resumeListener)

        // When we load a component that is not recent
        val intent = Intent(Intent.ACTION_USER_UNLOCKED)
        intent.putExtra(Intent.EXTRA_USER_HANDLE, context.userId)
        resumeListener.userUnlockReceiver.onReceive(mockContext, intent)

        // We do not try to add resume controls
        verify(resumeBrowser, times(0)).findRecentMedia()
        verify(mediaDataManager, times(0))
            .addResumptionControls(anyInt(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun testOnLoad_hasService_updatesLastPlayed() {
        // Set up browser to return successfully
        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.token).thenReturn(token)
        whenever(resumeBrowser.appIntent).thenReturn(pendingIntent)
        whenever(resumeBrowser.findRecentMedia()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // Set up shared preferences to have a component with a lastplayed time
        val currentTime = clock.currentTimeMillis()
        val lastPlayed = currentTime - 1000
        val componentsString = "$PACKAGE_NAME/$CLASS_NAME/$lastPlayed:"
        whenever(sharedPrefs.getString(any(), any())).thenReturn(componentsString)
        val resumeListener =
            MediaResumeListener(
                mockContext,
                broadcastDispatcher,
                userTracker,
                executor,
                executor,
                tunerService,
                resumeBrowserFactory,
                dumpManager,
                clock
            )
        resumeListener.setManager(mediaDataManager)
        mediaDataManager.addListener(resumeListener)

        // When media data is loaded that has not been checked yet, and does have a MBS
        val dataCopy = data.copy(resumeAction = null, hasCheckedForResume = false)
        resumeListener.onMediaDataLoaded(KEY, null, dataCopy)

        // Then we store the new lastPlayed time
        verify(sharedPrefsEditor).putString(any(), (capture(componentCaptor)))
        componentCaptor.value
            .split(ResumeMediaBrowser.DELIMITER.toRegex())
            .dropLastWhile { it.isEmpty() }
            .forEach {
                val result = it.split("/")
                assertThat(result.size).isEqualTo(3)
                assertThat(result[2].toLong()).isEqualTo(currentTime)
            }
        verify(sharedPrefsEditor, times(1)).apply()
    }

    @Test
    fun testOnMediaDataLoaded_newKeyDifferent_oldMediaBrowserDisconnected() {
        setUpMbsWithValidResolveInfo()

        resumeListener.onMediaDataLoaded(key = KEY, oldKey = null, data)
        executor.runAllReady()

        resumeListener.onMediaDataLoaded(key = "newKey", oldKey = KEY, data)

        verify(resumeBrowser).disconnect()
    }

    @Test
    fun testOnMediaDataLoaded_updatingResumptionListError_mediaBrowserDisconnected() {
        setUpMbsWithValidResolveInfo()

        // Set up mocks to return with an error
        whenever(resumeBrowser.testConnection()).thenAnswer { callbackCaptor.value.onError() }

        resumeListener.onMediaDataLoaded(key = KEY, oldKey = null, data)
        executor.runAllReady()

        // Ensure we disconnect the browser
        verify(resumeBrowser).disconnect()
    }

    @Test
    fun testOnMediaDataLoaded_trackAdded_mediaBrowserDisconnected() {
        setUpMbsWithValidResolveInfo()

        // Set up mocks to return with a track added
        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.testConnection()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        resumeListener.onMediaDataLoaded(key = KEY, oldKey = null, data)
        executor.runAllReady()

        // Ensure we disconnect the browser
        verify(resumeBrowser).disconnect()
    }

    @Test
    fun testResumeAction_oldMediaBrowserDisconnected() {
        setUpMbsWithValidResolveInfo()

        val description = MediaDescription.Builder().setTitle(TITLE).build()
        val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
        whenever(resumeBrowser.testConnection()).thenAnswer {
            callbackCaptor.value.addTrack(description, component, resumeBrowser)
        }

        // Load media data that will require us to get the resume action
        val dataCopy = data.copy(resumeAction = null, hasCheckedForResume = false)
        resumeListener.onMediaDataLoaded(KEY, null, dataCopy)
        executor.runAllReady()
        verify(mediaDataManager, times(2)).setResumeAction(eq(KEY), capture(actionCaptor))

        // Set up our factory to return a new browser so we can verify we disconnected the old one
        val newResumeBrowser = mock(ResumeMediaBrowser::class.java)
        whenever(resumeBrowserFactory.create(capture(callbackCaptor), any()))
            .thenReturn(newResumeBrowser)

        // When the resume action is run
        actionCaptor.value.run()

        // Then we disconnect the old one
        verify(resumeBrowser).disconnect()
    }

    /** Sets up mocks to successfully find a MBS that returns valid media. */
    private fun setUpMbsWithValidResolveInfo() {
        val pm = mock(PackageManager::class.java)
        whenever(mockContext.packageManager).thenReturn(pm)
        val resolveInfo = ResolveInfo()
        val serviceInfo = ServiceInfo()
        serviceInfo.packageName = PACKAGE_NAME
        resolveInfo.serviceInfo = serviceInfo
        resolveInfo.serviceInfo.name = CLASS_NAME
        val resumeInfo = listOf(resolveInfo)
        whenever(pm.queryIntentServices(any(), anyInt())).thenReturn(resumeInfo)
    }
}
