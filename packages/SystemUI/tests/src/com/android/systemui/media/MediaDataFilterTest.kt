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

import android.app.smartspace.SmartspaceAction
import android.graphics.Color
import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

private const val KEY = "TEST_KEY"
private const val KEY_ALT = "TEST_KEY_2"
private const val USER_MAIN = 0
private const val USER_GUEST = 10
private const val APP = "APP"
private const val BG_COLOR = Color.RED
private const val PACKAGE = "PKG"
private const val ARTIST = "ARTIST"
private const val TITLE = "TITLE"
private const val DEVICE_NAME = "DEVICE_NAME"
private const val SMARTSPACE_KEY = "SMARTSPACE_KEY"

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> any(): T = Mockito.any()

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaDataFilterTest : SysuiTestCase() {

    @Mock
    private lateinit var listener: MediaDataManager.Listener
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var mediaResumeListener: MediaResumeListener
    @Mock
    private lateinit var mediaDataManager: MediaDataManager
    @Mock
    private lateinit var lockscreenUserManager: NotificationLockscreenUserManager
    @Mock
    private lateinit var executor: Executor
    @Mock
    private lateinit var smartspaceData: SmartspaceMediaData
    @Mock
    private lateinit var smartspaceMediaRecommendationItem: SmartspaceAction

    private lateinit var mediaDataFilter: MediaDataFilter
    private lateinit var dataMain: MediaData
    private lateinit var dataGuest: MediaData
    private val device = MediaDeviceData(true, null, DEVICE_NAME)
    private val clock = FakeSystemClock()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mediaDataFilter = MediaDataFilter(context, broadcastDispatcher, mediaResumeListener,
                lockscreenUserManager, executor, clock)
        mediaDataFilter.mediaDataManager = mediaDataManager
        mediaDataFilter.addListener(listener)

        // Start all tests as main user
        setUser(USER_MAIN)

        // Set up test media data
        dataMain = MediaData(USER_MAIN, true, BG_COLOR, APP, null, ARTIST, TITLE, null, emptyList(),
            emptyList(), PACKAGE, null, null, device, true, null)

        dataGuest = MediaData(USER_GUEST, true, BG_COLOR, APP, null, ARTIST, TITLE, null,
            emptyList(), emptyList(), PACKAGE, null, null, device, true, null)

        `when`(smartspaceData.targetId).thenReturn(SMARTSPACE_KEY)
        `when`(smartspaceData.isActive).thenReturn(true)
        `when`(smartspaceData.isValid).thenReturn(true)
        `when`(smartspaceData.packageName).thenReturn(PACKAGE)
        `when`(smartspaceData.recommendations).thenReturn(listOf(smartspaceMediaRecommendationItem))
    }

    private fun setUser(id: Int) {
        `when`(lockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(false)
        `when`(lockscreenUserManager.isCurrentProfile(eq(id))).thenReturn(true)
        mediaDataFilter.handleUserSwitched(id)
    }

    @Test
    fun testOnDataLoadedForCurrentUser_callsListener() {
        // GIVEN a media for main user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)

        // THEN we should tell the listener
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), eq(dataMain), eq(true),
                eq(false))
    }

    @Test
    fun testOnDataLoadedForGuest_doesNotCallListener() {
        // GIVEN a media for guest user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataGuest)

        // THEN we should NOT tell the listener
        verify(listener, never()).onMediaDataLoaded(any(), any(), any(), anyBoolean(), anyBoolean())
    }

    @Test
    fun testOnRemovedForCurrent_callsListener() {
        // GIVEN a media was removed for main user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
        mediaDataFilter.onMediaDataRemoved(KEY)

        // THEN we should tell the listener
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testOnRemovedForGuest_doesNotCallListener() {
        // GIVEN a media was removed for guest user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataGuest)
        mediaDataFilter.onMediaDataRemoved(KEY)

        // THEN we should NOT tell the listener
        verify(listener, never()).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testOnUserSwitched_removesOldUserControls() {
        // GIVEN that we have a media loaded for main user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)

        // and we switch to guest user
        setUser(USER_GUEST)

        // THEN we should remove the main user's media
        verify(listener).onMediaDataRemoved(eq(KEY))
    }

    @Test
    fun testOnUserSwitched_addsNewUserControls() {
        // GIVEN that we had some media for both users
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
        mediaDataFilter.onMediaDataLoaded(KEY_ALT, null, dataGuest)
        reset(listener)

        // and we switch to guest user
        setUser(USER_GUEST)

        // THEN we should add back the guest user media
        verify(listener).onMediaDataLoaded(eq(KEY_ALT), eq(null), eq(dataGuest), eq(true),
                eq(false))

        // but not the main user's
        verify(listener, never()).onMediaDataLoaded(eq(KEY), any(), eq(dataMain), anyBoolean(),
                anyBoolean())
    }

    @Test
    fun testHasAnyMedia() {
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)
        assertThat(mediaDataFilter.hasAnyMedia()).isTrue()
    }

    @Test
    fun testHasActiveMedia() {
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        val data = dataMain.copy(active = true)

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)
        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
    }

    @Test
    fun testHasAnyMedia_onlyCurrentUser() {
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataGuest)
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun testHasActiveMedia_onlyCurrentUser() {
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        val data = dataGuest.copy(active = true)

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnNotificationRemoved_doesntHaveMedia() {
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)
        mediaDataFilter.onMediaDataRemoved(KEY)
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun testOnSwipeToDismiss_setsTimedOut() {
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
        mediaDataFilter.onSwipeToDismiss()

        verify(mediaDataManager).setTimedOut(eq(KEY), eq(true), eq(true))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noMedia_activeValidRec_prioritizesSmartspace() {
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener)
                .onSmartspaceMediaDataLoaded(eq(SMARTSPACE_KEY), eq(smartspaceData), eq(true))
        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener, never()).onMediaDataLoaded(any(), any(), any(), anyBoolean(), anyBoolean())
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noRecentMedia_activeValidRec_prioritizesSmartspace() {
        val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
        clock.advanceTime(SMARTSPACE_MAX_AGE + 100)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener)
                .onSmartspaceMediaDataLoaded(eq(SMARTSPACE_KEY), eq(smartspaceData), eq(true))
        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noRecentMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
        clock.advanceTime(SMARTSPACE_MAX_AGE + 100)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true),
                eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as not active instead
        verify(listener, never()).onMediaDataLoaded(eq(KEY), eq(KEY), any(), anyBoolean(),
                anyBoolean())
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_activeInvalidRec_usesMedia() {
        `when`(smartspaceData.isValid).thenReturn(false)

        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true),
                eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as active instead
        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(KEY), eq(dataCurrentAndActive), eq(true),
                eq(true))
        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
        // Smartspace update shouldn't be propagated for the empty rec list.
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_activeValidRec_usesBoth() {
        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true),
                eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as active instead
        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(KEY), eq(dataCurrentAndActive), eq(true),
                eq(true))
        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
        // Smartspace update should also be propagated but not prioritized.
        verify(listener)
                .onSmartspaceMediaDataLoaded(eq(SMARTSPACE_KEY), eq(smartspaceData), eq(false))
    }

    @Test
    fun testOnSmartspaceMediaDataRemoved_usedSmartspace_clearsSmartspace() {
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

        verify(listener).onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnSmartspaceMediaDataRemoved_usedMediaAndSmartspace_clearsBoth() {
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true),
                eq(false))

        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener).onMediaDataLoaded(eq(KEY), eq(KEY), eq(dataCurrentAndActive), eq(true),
                eq(true))

        mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

        verify(listener).onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }
}
