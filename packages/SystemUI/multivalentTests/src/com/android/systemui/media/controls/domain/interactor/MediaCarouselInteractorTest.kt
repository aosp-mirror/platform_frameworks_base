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
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaCarouselInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl = kosmos.mediaDataFilter
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val mediaFilterRepository: MediaFilterRepository = kosmos.mediaFilterRepository

    private val underTest: MediaCarouselInteractor = kosmos.mediaCarouselInteractor

    @Before
    fun setUp() {
        underTest.start()
    }

    @Test
    fun addUserMediaEntry_activeThenInactivate() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasActiveMedia by collectLastValue(underTest.hasActiveMedia)
            val hasAnyMedia by collectLastValue(underTest.hasAnyMedia)

            val userMedia = MediaData(active = true)

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasActiveMedia).isTrue()
            assertThat(hasAnyMedia).isTrue()

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia.copy(active = false))

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasActiveMedia).isFalse()
            assertThat(hasAnyMedia).isTrue()
        }

    @Test
    fun addInactiveUserMediaEntry_thenRemove() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasActiveMedia by collectLastValue(underTest.hasActiveMedia)
            val hasAnyMedia by collectLastValue(underTest.hasAnyMedia)

            val userMedia = MediaData(active = false)
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasActiveMedia).isFalse()
            assertThat(hasAnyMedia).isTrue()

            assertThat(mediaFilterRepository.removeSelectedUserMediaEntry(instanceId, userMedia))
                .isTrue()

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasActiveMedia).isFalse()
            assertThat(hasAnyMedia).isFalse()
        }

    @Test
    fun addActiveRecommendation_inactiveMedia() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasAnyMediaOrRecommendation by
                collectLastValue(underTest.hasAnyMediaOrRecommendation)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val userMediaRecommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )
            val userMedia = MediaData(active = false)

            mediaFilterRepository.setRecommendation(userMediaRecommendation)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()
        }

    @Test
    fun addActiveRecommendation_thenInactive() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasAnyMediaOrRecommendation by
                collectLastValue(underTest.hasAnyMediaOrRecommendation)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val mediaRecommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )

            mediaFilterRepository.setRecommendation(mediaRecommendation)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()

            mediaFilterRepository.setRecommendation(mediaRecommendation.copy(isActive = false))

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasAnyMediaOrRecommendation).isFalse()
        }

    @Test
    fun addActiveRecommendation_thenInvalid() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasAnyMediaOrRecommendation by
                collectLastValue(underTest.hasAnyMediaOrRecommendation)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val mediaRecommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )

            mediaFilterRepository.setRecommendation(mediaRecommendation)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()

            mediaFilterRepository.setRecommendation(
                mediaRecommendation.copy(recommendations = listOf())
            )

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasAnyMediaOrRecommendation).isFalse()
        }

    @Test
    fun hasAnyMedia_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasAnyMedia.value).isFalse() }

    @Test
    fun hasAnyMediaOrRecommendation_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasAnyMediaOrRecommendation.value).isFalse() }

    @Test
    fun hasActiveMedia_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasActiveMedia.value).isFalse() }

    @Test
    fun hasActiveMediaOrRecommendation_nothingSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasActiveMediaOrRecommendation.value).isFalse() }

    @Test
    fun onMediaDataUpdated_updatesLoadingState() =
        testScope.runTest {
            whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
            whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
            val mediaDataLoadedStates by collectLastValue(underTest.mediaDataLoadedStates)
            val instanceId = InstanceId.fakeInstanceId(123)
            val mediaLoadedStates: MutableList<MediaDataLoadingModel> = mutableListOf()

            mediaLoadedStates.add(MediaDataLoadingModel.Loaded(instanceId))
            mediaDataFilter.onMediaDataLoaded(
                KEY,
                KEY,
                MediaData(userId = USER_ID, instanceId = instanceId)
            )

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStates)

            val newInstanceId = InstanceId.fakeInstanceId(321)

            mediaLoadedStates.add(MediaDataLoadingModel.Loaded(newInstanceId))
            mediaDataFilter.onMediaDataLoaded(
                KEY_2,
                KEY_2,
                MediaData(userId = USER_ID, instanceId = newInstanceId)
            )

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStates)

            mediaLoadedStates.remove(MediaDataLoadingModel.Loaded(instanceId))

            mediaDataFilter.onMediaDataRemoved(KEY)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStates)

            mediaLoadedStates.remove(MediaDataLoadingModel.Loaded(newInstanceId))

            mediaDataFilter.onMediaDataRemoved(KEY_2)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStates)
        }

    @Test
    fun onMediaRecommendationsUpdated_updatesLoadingState() =
        testScope.runTest {
            whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
            whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
            val recommendationsLoadingState by
                collectLastValue(underTest.recommendationsLoadingState)
            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val mediaRecommendations =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )
            var recommendationsLoadingModel: SmartspaceMediaLoadingModel =
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, isPrioritized = true)

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, mediaRecommendations)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)

            recommendationsLoadingModel = SmartspaceMediaLoadingModel.Removed(KEY_MEDIA_SMARTSPACE)

            mediaDataFilter.onSmartspaceMediaDataRemoved(KEY_MEDIA_SMARTSPACE)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
        }

    companion object {
        private const val KEY = "key"
        private const val KEY_2 = "key2"
        private const val USER_ID = 0
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
    }
}
