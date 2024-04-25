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
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaRecommendationsInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaRecommendationsInteractor
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.testKosmos
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

    private val mediaFilterRepository: MediaFilterRepository = kosmos.mediaFilterRepository
    private val mediaRecommendationsInteractor: MediaRecommendationsInteractor =
        kosmos.mediaRecommendationsInteractor

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
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(hasActiveMedia).isFalse()
            assertThat(hasAnyMedia).isTrue()

            assertThat(mediaFilterRepository.removeSelectedUserMediaEntry(instanceId, userMedia))
                .isTrue()
            mediaFilterRepository.addMediaDataLoadingState(
                MediaDataLoadingModel.Removed(instanceId)
            )

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
            val sortedMedia by collectLastValue(underTest.sortedMedia)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val userMediaRecommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )
            val userMedia = MediaData(active = false)
            val recsLoadingModel = SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            val mediaLoadingModel = MediaDataLoadingModel.Loaded(userMedia.instanceId)

            mediaFilterRepository.setRecommendation(userMediaRecommendation)
            mediaFilterRepository.setRecommendationsLoadingState(recsLoadingModel)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()
            assertThat(sortedMedia)
                .containsExactly(MediaCommonModel.MediaRecommendations(recsLoadingModel))

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()
            assertThat(sortedMedia)
                .containsExactly(
                    MediaCommonModel.MediaRecommendations(recsLoadingModel),
                    MediaCommonModel.MediaControl(mediaLoadingModel, true)
                )
                .inOrder()
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
    fun loadMediaFromRec() =
        testScope.runTest {
            val isMediaFromRec by collectLastValue(underTest.isMediaFromRec)
            val instanceId = InstanceId.fakeInstanceId(123)
            val data = MediaData(active = true, instanceId = instanceId, packageName = PACKAGE_NAME)

            assertThat(isMediaFromRec).isFalse()

            mediaRecommendationsInteractor.switchToMediaControl(PACKAGE_NAME)
            mediaFilterRepository.addSelectedUserMediaEntry(data)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(isMediaFromRec).isFalse()

            mediaFilterRepository.addSelectedUserMediaEntry(data.copy(isPlaying = true))
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(isMediaFromRec).isTrue()
        }

    companion object {
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val PACKAGE_NAME = "com.android.example"
    }
}
