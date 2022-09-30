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

package com.android.systemui.media.controls.pipeline

import android.app.smartspace.SmartspaceAction
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import com.android.systemui.media.controls.ui.MediaPlayerData
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

private const val KEY = "TEST_KEY"
private const val KEY_ALT = "TEST_KEY_2"
private const val USER_MAIN = 0
private const val USER_GUEST = 10
private const val PACKAGE = "PKG"
private val INSTANCE_ID = InstanceId.fakeInstanceId(123)!!
private const val APP_UID = 99
private const val SMARTSPACE_KEY = "SMARTSPACE_KEY"
private const val SMARTSPACE_PACKAGE = "SMARTSPACE_PKG"
private val SMARTSPACE_INSTANCE_ID = InstanceId.fakeInstanceId(456)!!

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaDataFilterTest : SysuiTestCase() {

    @Mock private lateinit var listener: MediaDataManager.Listener
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var broadcastSender: BroadcastSender
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var lockscreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var executor: Executor
    @Mock private lateinit var smartspaceData: SmartspaceMediaData
    @Mock private lateinit var smartspaceMediaRecommendationItem: SmartspaceAction
    @Mock private lateinit var logger: MediaUiEventLogger

    private lateinit var mediaDataFilter: MediaDataFilter
    private lateinit var dataMain: MediaData
    private lateinit var dataGuest: MediaData
    private val clock = FakeSystemClock()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        MediaPlayerData.clear()
        mediaDataFilter =
            MediaDataFilter(
                context,
                userTracker,
                broadcastSender,
                lockscreenUserManager,
                executor,
                clock,
                logger
            )
        mediaDataFilter.mediaDataManager = mediaDataManager
        mediaDataFilter.addListener(listener)

        // Start all tests as main user
        setUser(USER_MAIN)

        // Set up test media data
        dataMain =
            MediaTestUtils.emptyMediaData.copy(
                userId = USER_MAIN,
                packageName = PACKAGE,
                instanceId = INSTANCE_ID,
                appUid = APP_UID
            )
        dataGuest = dataMain.copy(userId = USER_GUEST)

        `when`(smartspaceData.targetId).thenReturn(SMARTSPACE_KEY)
        `when`(smartspaceData.isActive).thenReturn(true)
        `when`(smartspaceData.isValid()).thenReturn(true)
        `when`(smartspaceData.packageName).thenReturn(SMARTSPACE_PACKAGE)
        `when`(smartspaceData.recommendations).thenReturn(listOf(smartspaceMediaRecommendationItem))
        `when`(smartspaceData.headphoneConnectionTimeMillis)
            .thenReturn(clock.currentTimeMillis() - 100)
        `when`(smartspaceData.instanceId).thenReturn(SMARTSPACE_INSTANCE_ID)
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
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(dataMain), eq(true), eq(0), eq(false))
    }

    @Test
    fun testOnDataLoadedForGuest_doesNotCallListener() {
        // GIVEN a media for guest user
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataGuest)

        // THEN we should NOT tell the listener
        verify(listener, never())
            .onMediaDataLoaded(any(), any(), any(), anyBoolean(), anyInt(), anyBoolean())
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
        verify(listener)
            .onMediaDataLoaded(eq(KEY_ALT), eq(null), eq(dataGuest), eq(true), eq(0), eq(false))

        // but not the main user's
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), any(), eq(dataMain), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun hasAnyMedia_noMediaSet_returnsFalse() {
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun hasAnyMedia_mediaSet_returnsTrue() {
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)

        assertThat(mediaDataFilter.hasAnyMedia()).isTrue()
    }

    @Test
    fun hasAnyMedia_recommendationSet_returnsFalse() {
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun hasAnyMediaOrRecommendation_noMediaSet_returnsFalse() {
        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isFalse()
    }

    @Test
    fun hasAnyMediaOrRecommendation_mediaSet_returnsTrue() {
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)

        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isTrue()
    }

    @Test
    fun hasAnyMediaOrRecommendation_recommendationSet_returnsTrue() {
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isTrue()
    }

    @Test
    fun hasActiveMedia_noMediaSet_returnsFalse() {
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun hasActiveMedia_inactiveMediaSet_returnsFalse() {
        val data = dataMain.copy(active = false)
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun hasActiveMedia_activeMediaSet_returnsTrue() {
        val data = dataMain.copy(active = true)
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

        assertThat(mediaDataFilter.hasActiveMedia()).isTrue()
    }

    @Test
    fun hasActiveMediaOrRecommendation_nothingSet_returnsFalse() {
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
    }

    @Test
    fun hasActiveMediaOrRecommendation_inactiveMediaSet_returnsFalse() {
        val data = dataMain.copy(active = false)
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
    }

    @Test
    fun hasActiveMediaOrRecommendation_activeMediaSet_returnsTrue() {
        val data = dataMain.copy(active = true)
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
    }

    @Test
    fun hasActiveMediaOrRecommendation_inactiveRecommendationSet_returnsFalse() {
        `when`(smartspaceData.isActive).thenReturn(false)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
    }

    @Test
    fun hasActiveMediaOrRecommendation_invalidRecommendationSet_returnsFalse() {
        `when`(smartspaceData.isValid()).thenReturn(false)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
    }

    @Test
    fun hasActiveMediaOrRecommendation_activeAndValidRecommendationSet_returnsTrue() {
        `when`(smartspaceData.isActive).thenReturn(true)
        `when`(smartspaceData.isValid()).thenReturn(true)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
    }

    @Test
    fun testHasAnyMediaOrRecommendation_onlyCurrentUser() {
        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isFalse()

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataGuest)
        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun testHasActiveMediaOrRecommendation_onlyCurrentUser() {
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        val data = dataGuest.copy(active = true)

        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasAnyMedia()).isFalse()
    }

    @Test
    fun testOnNotificationRemoved_doesntHaveMedia() {
        mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)
        mediaDataFilter.onMediaDataRemoved(KEY)
        assertThat(mediaDataFilter.hasAnyMediaOrRecommendation()).isFalse()
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
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
        verify(logger, never()).logRecommendationActivated(any(), any(), any())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener, never())
            .onMediaDataLoaded(any(), any(), any(), anyBoolean(), anyInt(), anyBoolean())
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        verify(logger, never()).logRecommendationAdded(any(), any())
        verify(logger, never()).logRecommendationActivated(any(), any(), any())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noRecentMedia_activeValidRec_prioritizesSmartspace() {
        val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
        clock.advanceTime(SMARTSPACE_MAX_AGE + 100)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener)
            .onSmartspaceMediaDataLoaded(eq(SMARTSPACE_KEY), eq(smartspaceData), eq(true))
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
        verify(logger, never()).logRecommendationActivated(any(), any(), any())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_noRecentMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
        clock.advanceTime(SMARTSPACE_MAX_AGE + 100)
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        verify(logger, never()).logRecommendationAdded(any(), any())
        verify(logger, never()).logRecommendationActivated(any(), any(), any())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_inactiveRec_showsNothing() {
        `when`(smartspaceData.isActive).thenReturn(false)

        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true), eq(0), eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as not active instead
        verify(listener, never())
            .onMediaDataLoaded(eq(KEY), eq(KEY), any(), anyBoolean(), anyInt(), anyBoolean())
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
        verify(logger, never()).logRecommendationAdded(any(), any())
        verify(logger, never()).logRecommendationActivated(any(), any(), any())
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_activeInvalidRec_usesMedia() {
        `when`(smartspaceData.isValid()).thenReturn(false)

        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true), eq(0), eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as active instead
        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                eq(dataCurrentAndActive),
                eq(true),
                eq(100),
                eq(true)
            )
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
        // Smartspace update shouldn't be propagated for the empty rec list.
        verify(listener, never()).onSmartspaceMediaDataLoaded(any(), any(), anyBoolean())
        verify(logger, never()).logRecommendationAdded(any(), any())
        verify(logger).logRecommendationActivated(eq(APP_UID), eq(PACKAGE), eq(INSTANCE_ID))
    }

    @Test
    fun testOnSmartspaceMediaDataLoaded_hasRecentMedia_activeValidRec_usesBoth() {
        // WHEN we have media that was recently played, but not currently active
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true), eq(0), eq(false))

        // AND we get a smartspace signal
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        // THEN we should tell listeners to treat the media as active instead
        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                eq(dataCurrentAndActive),
                eq(true),
                eq(100),
                eq(true)
            )
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isTrue()
        // Smartspace update should also be propagated but not prioritized.
        verify(listener)
            .onSmartspaceMediaDataLoaded(eq(SMARTSPACE_KEY), eq(smartspaceData), eq(false))
        verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
        verify(logger).logRecommendationActivated(eq(APP_UID), eq(PACKAGE), eq(INSTANCE_ID))
    }

    @Test
    fun testOnSmartspaceMediaDataRemoved_usedSmartspace_clearsSmartspace() {
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
        mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

        verify(listener).onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }

    @Test
    fun testOnSmartspaceMediaDataRemoved_usedMediaAndSmartspace_clearsBoth() {
        val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
        verify(listener)
            .onMediaDataLoaded(eq(KEY), eq(null), eq(dataCurrent), eq(true), eq(0), eq(false))

        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

        val dataCurrentAndActive = dataCurrent.copy(active = true)
        verify(listener)
            .onMediaDataLoaded(
                eq(KEY),
                eq(KEY),
                eq(dataCurrentAndActive),
                eq(true),
                eq(100),
                eq(true)
            )

        mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

        verify(listener).onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)
        assertThat(mediaDataFilter.hasActiveMediaOrRecommendation()).isFalse()
        assertThat(mediaDataFilter.hasActiveMedia()).isFalse()
    }
}
