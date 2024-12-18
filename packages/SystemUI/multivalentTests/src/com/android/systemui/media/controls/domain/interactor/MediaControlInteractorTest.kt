/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.media.controls.domain.interactor

import android.R
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.activityIntentHelper
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.mockBroadcastDialogController
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.data.repository.mediaDataRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaControlInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaControlInteractor
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.domain.pipeline.mediaDataProcessor
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.util.MediaSmartspaceLogger.Companion.SMARTSPACE_CARD_CLICK_EVENT
import com.android.systemui.media.controls.util.MediaSmartspaceLogger.Companion.SMARTSPACE_CARD_DISMISS_EVENT
import com.android.systemui.media.controls.util.mediaInstanceId
import com.android.systemui.media.controls.util.mediaSmartspaceLogger
import com.android.systemui.media.controls.util.mockMediaSmartspaceLogger
import com.android.systemui.media.mediaOutputDialogManager
import com.android.systemui.mockActivityIntentHelper
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaControlInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl =
        with(kosmos) {
            mediaSmartspaceLogger = mockMediaSmartspaceLogger
            mediaDataFilter
        }
    private val activityStarter = kosmos.activityStarter
    private val keyguardStateController = kosmos.keyguardStateController
    private val instanceId: InstanceId = kosmos.mediaInstanceId
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val smartspaceLogger = kosmos.mockMediaSmartspaceLogger
    private val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val mediaRecommendation =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )

    private val underTest: MediaControlInteractor =
        with(kosmos) {
            activityIntentHelper = mockActivityIntentHelper
            kosmos.mediaControlInteractor
        }

    @Test
    fun onMediaDataUpdated() =
        testScope.runTest {
            whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
            whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
            val controlModel by collectLastValue(underTest.mediaControl)
            var mediaData = MediaData(userId = USER_ID, instanceId = instanceId, artist = ARTIST)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isEqualTo(instanceId)
            assertThat(controlModel?.artistName).isEqualTo(ARTIST)

            mediaData = MediaData(userId = USER_ID, instanceId = instanceId, artist = ARTIST_2)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isEqualTo(instanceId)
            assertThat(controlModel?.artistName).isEqualTo(ARTIST_2)

            mediaData =
                MediaData(
                    userId = USER_ID,
                    instanceId = InstanceId.fakeInstanceId(2),
                    artist = ARTIST,
                )

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isNotEqualTo(mediaData.instanceId)
            assertThat(controlModel?.artistName).isEqualTo(ARTIST_2)
        }

    @Test
    fun startSettings() {
        underTest.startSettings()

        verify(activityStarter).startActivity(any(), eq(true))
    }

    @Test
    fun startClickIntent_showOverLockscreen() {
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(kosmos.activityIntentHelper.wouldPendingShowOverLockscreen(any(), any()))
            .thenReturn(true)

        val clickIntent = mock<PendingIntent> { whenever(it.isActivity).thenReturn(true) }
        val expandable = mock<Expandable>()
        val activityController = mock<ActivityTransitionAnimator.Controller>()
        whenever(expandable.activityTransitionController(any())).thenReturn(activityController)

        underTest.startClickIntent(expandable, clickIntent, SMARTSPACE_CARD_CLICK_EVENT, 1)

        verify(activityStarter)
            .startPendingIntentMaybeDismissingKeyguard(
                eq(clickIntent),
                eq(null),
                eq(activityController),
            )
    }

    @Test
    fun startClickIntent_hideOverLockscreen() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        whenever(keyguardStateController.isShowing).thenReturn(false)

        val clickIntent = mock<PendingIntent> { whenever(it.isActivity).thenReturn(true) }
        val expandable = mock<Expandable>()
        val activityController = mock<ActivityTransitionAnimator.Controller>()
        whenever(expandable.activityTransitionController(any())).thenReturn(activityController)

        val mediaData = MediaData(userId = USER_ID, instanceId = instanceId, artist = ARTIST)
        mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, mediaRecommendation, true)
        mediaDataFilter.onMediaDataLoaded(KEY, null, mediaData)
        underTest.startClickIntent(expandable, clickIntent, SMARTSPACE_CARD_CLICK_EVENT, 1)

        verify(smartspaceLogger)
            .logSmartspaceCardUIEvent(
                SMARTSPACE_CARD_CLICK_EVENT,
                mediaData.smartspaceId,
                mediaData.appUid,
                surface = SURFACE,
                cardinality = 2,
                rank = 1,
            )
        verify(activityStarter)
            .postStartActivityDismissingKeyguard(eq(clickIntent), eq(activityController))
    }

    @Test
    fun startDeviceIntent_showOverLockscreen() {
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(kosmos.activityIntentHelper.wouldPendingShowOverLockscreen(any(), any()))
            .thenReturn(true)

        val deviceIntent = mock<PendingIntent> { whenever(it.isActivity).thenReturn(true) }

        underTest.startDeviceIntent(deviceIntent)

        verify(deviceIntent).send(any<Bundle>())
    }

    @Test
    fun startDeviceIntent_intentNotActivity() {
        whenever(keyguardStateController.isShowing).thenReturn(true)
        whenever(kosmos.activityIntentHelper.wouldPendingShowOverLockscreen(any(), any()))
            .thenReturn(true)

        val deviceIntent = mock<PendingIntent> { whenever(it.isActivity).thenReturn(false) }

        underTest.startDeviceIntent(deviceIntent)

        verify(deviceIntent, never()).send(any<Bundle>())
    }

    @Test
    fun startDeviceIntent_hideOverLockscreen() {
        whenever(keyguardStateController.isShowing).thenReturn(false)

        val deviceIntent = mock<PendingIntent> { whenever(it.isActivity).thenReturn(true) }

        underTest.startDeviceIntent(deviceIntent)

        verify(activityStarter).postStartActivityDismissingKeyguard(eq(deviceIntent))
    }

    @Test
    fun startMediaOutputDialog() {
        val expandable = mock<Expandable>()
        val dialogTransitionController = mock<DialogTransitionAnimator.Controller>()
        whenever(expandable.dialogTransitionController(any()))
            .thenReturn(dialogTransitionController)

        underTest.startMediaOutputDialog(expandable, PACKAGE_NAME)

        verify(kosmos.mediaOutputDialogManager)
            .createAndShowWithController(
                eq(PACKAGE_NAME),
                eq(true),
                eq(dialogTransitionController),
                eq(null),
                eq(null),
            )
    }

    @Test
    fun startBroadcastDialog() {
        val expandable = mock<Expandable>()
        val dialogTransitionController = mock<DialogTransitionAnimator.Controller>()
        whenever(expandable.dialogTransitionController()).thenReturn(dialogTransitionController)

        underTest.startBroadcastDialog(expandable, APP_NAME, PACKAGE_NAME)

        verify(kosmos.mockBroadcastDialogController)
            .createBroadcastDialogWithController(
                eq(APP_NAME),
                eq(PACKAGE_NAME),
                eq(dialogTransitionController),
            )
    }

    @Test
    fun removeMediaControl_noRecommendation() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        val listener = mock<MediaDataProcessor.Listener>()
        kosmos.mediaDataProcessor.addInternalListener(listener)

        val mediaData = MediaData(userId = USER_ID, instanceId = instanceId, artist = ARTIST)
        kosmos.mediaDataRepository.addMediaEntry(KEY, mediaData)
        kosmos.mediaDataFilter.onMediaDataLoaded(KEY, null, mediaData)

        underTest.removeMediaControl(null, instanceId, 0L, SMARTSPACE_CARD_DISMISS_EVENT, 1)
        kosmos.fakeExecutor.advanceClockToNext()
        kosmos.fakeExecutor.runAllReady()

        verify(smartspaceLogger, never())
            .logSmartspaceCardUIEvent(
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean(),
            )
        verify(listener).onMediaDataRemoved(eq(KEY), eq(true))
    }

    @Test
    fun removeMediaControl_recommendationsExist() {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        val listener = mock<MediaDataProcessor.Listener>()
        kosmos.mediaDataProcessor.addInternalListener(listener)

        val mediaData = MediaData(userId = USER_ID, instanceId = instanceId, artist = ARTIST)
        kosmos.mediaDataRepository.addMediaEntry(KEY, mediaData)
        mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, mediaRecommendation, true)
        mediaDataFilter.onMediaDataLoaded(KEY, null, mediaData)

        underTest.removeMediaControl(null, instanceId, 0L, SMARTSPACE_CARD_DISMISS_EVENT, 1)
        kosmos.fakeExecutor.advanceClockToNext()
        kosmos.fakeExecutor.runAllReady()

        verify(smartspaceLogger)
            .logSmartspaceCardUIEvent(
                SMARTSPACE_CARD_DISMISS_EVENT,
                mediaData.smartspaceId,
                mediaData.appUid,
                surface = SURFACE,
                cardinality = 2,
                rank = 1,
            )
        verify(listener).onMediaDataRemoved(eq(KEY), eq(true))
    }

    companion object {
        private const val USER_ID = 0
        private const val KEY = "key"
        private const val PACKAGE_NAME = "com.example.app"
        private const val APP_NAME = "app"
        private const val ARTIST = "artist"
        private const val ARTIST_2 = "artist2"
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val SURFACE = 4
    }
}
