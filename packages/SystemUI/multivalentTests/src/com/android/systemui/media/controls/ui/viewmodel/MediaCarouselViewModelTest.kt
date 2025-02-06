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

package com.android.systemui.media.controls.ui.viewmodel

import android.R
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaRecommendationsInteractor
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.mediaLogger
import com.android.systemui.media.controls.shared.mockMediaLogger
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaCarouselViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos().apply { mediaLogger = mockMediaLogger }
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl = kosmos.mediaDataFilter
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val packageManager = kosmos.packageManager
    private val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val drawable = context.getDrawable(R.drawable.ic_media_play)
    private val smartspaceMediaData: SmartspaceMediaData =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            packageName = PACKAGE_NAME,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )

    private val underTest: MediaCarouselViewModel = kosmos.mediaCarouselViewModel

    @Before
    fun setUp() {
        kosmos.mediaCarouselInteractor.start()

        whenever(packageManager.getApplicationIcon(Mockito.anyString())).thenReturn(drawable)
        whenever(packageManager.getApplicationIcon(any(ApplicationInfo::class.java)))
            .thenReturn(drawable)
        whenever(packageManager.getApplicationInfo(eq(PACKAGE_NAME), ArgumentMatchers.anyInt()))
            .thenReturn(ApplicationInfo())
        whenever(packageManager.getApplicationLabel(any())).thenReturn(PACKAGE_NAME)

        context.setMockPackageManager(packageManager)
    }

    @Test
    fun loadMediaControls_mediaItemsAreUpdated() =
        testScope.runTest {
            val sortedMedia by collectLastValue(underTest.mediaItems)
            val instanceId1 = InstanceId.fakeInstanceId(123)
            val instanceId2 = InstanceId.fakeInstanceId(456)

            loadMediaControl(KEY, instanceId1, isPlaying = true)
            loadMediaControl(KEY_2, instanceId2, isPlaying = true)
            loadMediaControl(KEY, instanceId1, isPlaying = false)

            var mediaControl2 = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            var mediaControl1 = sortedMedia?.get(1) as MediaCommonViewModel.MediaControl
            assertThat(mediaControl2.instanceId).isEqualTo(instanceId2)
            assertThat(mediaControl1.instanceId).isEqualTo(instanceId1)

            loadMediaControl(KEY, instanceId1, isPlaying = true)
            loadMediaControl(KEY_2, instanceId2, isPlaying = false)

            mediaControl2 = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            mediaControl1 = sortedMedia?.get(1) as MediaCommonViewModel.MediaControl
            assertThat(mediaControl2.instanceId).isEqualTo(instanceId2)
            assertThat(mediaControl1.instanceId).isEqualTo(instanceId1)

            underTest.onReorderingAllowed()

            mediaControl1 = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            mediaControl2 = sortedMedia?.get(1) as MediaCommonViewModel.MediaControl
            assertThat(mediaControl1.instanceId).isEqualTo(instanceId1)
            assertThat(mediaControl2.instanceId).isEqualTo(instanceId2)
        }

    @Test
    fun loadMediaControlsAndRecommendations_mediaItemsAreUpdated() =
        testScope.runTest {
            val sortedMedia by collectLastValue(underTest.mediaItems)
            val instanceId1 = InstanceId.fakeInstanceId(123)
            val instanceId2 = InstanceId.fakeInstanceId(456)

            loadMediaControl(KEY, instanceId1)
            loadMediaControl(KEY_2, instanceId2)
            loadMediaRecommendations()

            val firstMediaControl = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            val secondMediaControl = sortedMedia?.get(1) as MediaCommonViewModel.MediaControl
            val recsCard = sortedMedia?.get(2) as MediaCommonViewModel.MediaRecommendations
            assertThat(firstMediaControl.instanceId).isEqualTo(instanceId2)
            assertThat(secondMediaControl.instanceId).isEqualTo(instanceId1)
            assertThat(recsCard.key).isEqualTo(KEY_MEDIA_SMARTSPACE)
        }

    @Test
    fun recommendationClicked_switchToPlayer() =
        testScope.runTest {
            val sortedMedia by collectLastValue(underTest.mediaItems)
            kosmos.visualStabilityProvider.isReorderingAllowed = false
            val instanceId = InstanceId.fakeInstanceId(123)

            loadMediaRecommendations()
            kosmos.mediaRecommendationsInteractor.switchToMediaControl(PACKAGE_NAME)

            var recsCard = sortedMedia?.get(0) as MediaCommonViewModel.MediaRecommendations
            assertThat(sortedMedia).hasSize(1)
            assertThat(recsCard.key).isEqualTo(KEY_MEDIA_SMARTSPACE)

            loadMediaControl(KEY, instanceId, false)

            recsCard = sortedMedia?.get(0) as MediaCommonViewModel.MediaRecommendations
            assertThat(sortedMedia).hasSize(1)
            assertThat(recsCard.key).isEqualTo(KEY_MEDIA_SMARTSPACE)

            loadMediaControl(KEY, instanceId, true)

            val mediaControl = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            assertThat(sortedMedia).hasSize(2)
            assertThat(mediaControl.instanceId).isEqualTo(instanceId)
            assertThat(mediaControl.isMediaFromRec).isTrue()
        }

    @Test
    fun addMediaControlThenRemove_mediaEventsAreLogged() =
        testScope.runTest {
            val sortedMedia by collectLastValue(underTest.mediaItems)
            val instanceId = InstanceId.fakeInstanceId(123)

            loadMediaControl(KEY, instanceId)

            val mediaControl = sortedMedia?.get(0) as MediaCommonViewModel.MediaControl
            assertThat(mediaControl.instanceId).isEqualTo(instanceId)

            // when media control is added to carousel
            mediaControl.onAdded(mediaControl)

            verify(kosmos.mediaLogger).logMediaCardAdded(eq(instanceId))

            reset(kosmos.mediaLogger)

            // when media control is updated.
            mediaControl.onUpdated(mediaControl)

            verify(kosmos.mediaLogger, never()).logMediaCardAdded(eq(instanceId))

            mediaDataFilter.onMediaDataRemoved(KEY, true)
            assertThat(sortedMedia).isEmpty()

            // when media control is removed from carousel
            mediaControl.onRemoved(true)

            verify(kosmos.mediaLogger).logMediaCardRemoved(eq(instanceId))
        }

    @Test
    fun addMediaRecommendationThenRemove_mediaEventsAreLogged() =
        testScope.runTest {
            val sortedMedia by collectLastValue(underTest.mediaItems)

            loadMediaRecommendations()

            val mediaRecommendations =
                sortedMedia?.get(0) as MediaCommonViewModel.MediaRecommendations
            assertThat(mediaRecommendations.key).isEqualTo(KEY_MEDIA_SMARTSPACE)

            // when media recommendation is added to carousel
            mediaRecommendations.onAdded(mediaRecommendations)

            verify(kosmos.mediaLogger).logMediaRecommendationCardAdded(eq(KEY_MEDIA_SMARTSPACE))

            mediaDataFilter.onSmartspaceMediaDataRemoved(KEY, true)
            assertThat(sortedMedia).isEmpty()

            // when media recommendation is removed from carousel
            mediaRecommendations.onRemoved(true)

            verify(kosmos.mediaLogger).logMediaRecommendationCardRemoved(eq(KEY_MEDIA_SMARTSPACE))
        }

    private fun loadMediaControl(key: String, instanceId: InstanceId, isPlaying: Boolean = true) {
        whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
        whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
        val mediaData =
            MediaData(
                userId = USER_ID,
                packageName = PACKAGE_NAME,
                notificationKey = key,
                instanceId = instanceId,
                isPlaying = isPlaying,
            )

        mediaDataFilter.onMediaDataLoaded(key, key, mediaData)
    }

    private fun loadMediaRecommendations(key: String = KEY_MEDIA_SMARTSPACE) {
        mediaDataFilter.onSmartspaceMediaDataLoaded(key, smartspaceMediaData)
    }

    companion object {
        private const val USER_ID = 0
        private const val KEY = "key"
        private const val KEY_2 = "key2"
        private const val PACKAGE_NAME = "com.example.app"
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
    }
}
