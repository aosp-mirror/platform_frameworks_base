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
import android.os.Process
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
import com.android.systemui.media.controls.util.MediaSmartspaceLogger
import com.android.systemui.media.controls.util.SmallHash
import com.android.systemui.media.controls.util.mediaSmartspaceLogger
import com.android.systemui.media.controls.util.mockMediaSmartspaceLogger
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaCarouselInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaFilterRepository: MediaFilterRepository =
        with(kosmos) {
            mediaSmartspaceLogger = mockMediaSmartspaceLogger
            mediaFilterRepository
        }
    private val mediaRecommendationsInteractor: MediaRecommendationsInteractor =
        kosmos.mediaRecommendationsInteractor
    val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val mediaRecommendation =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            packageName = PACKAGE_NAME,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )
    private val smartspaceLogger = kosmos.mockMediaSmartspaceLogger

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

            val userMedia = MediaData(active = true)

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(underTest.hasActiveMedia()).isTrue()
            assertThat(underTest.hasAnyMedia()).isTrue()

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia.copy(active = false))

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isTrue()
        }

    @Test
    fun addInactiveUserMediaEntry_thenRemove() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)

            val userMedia = MediaData(active = false)
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isTrue()

            assertThat(mediaFilterRepository.removeSelectedUserMediaEntry(instanceId, userMedia))
                .isTrue()
            mediaFilterRepository.addMediaDataLoadingState(
                MediaDataLoadingModel.Removed(instanceId)
            )

            assertThat(hasActiveMediaOrRecommendation).isFalse()
            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isFalse()
        }

    @Test
    fun addActiveRecommendation_inactiveMedia() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasAnyMediaOrRecommendation by
                collectLastValue(underTest.hasAnyMediaOrRecommendation)
            val currentMedia by collectLastValue(underTest.currentMedia)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

            val userMedia = MediaData(active = false)
            val recsLoadingModel = SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            val mediaLoadingModel = MediaDataLoadingModel.Loaded(userMedia.instanceId)

            mediaFilterRepository.setRecommendation(mediaRecommendation)
            mediaFilterRepository.setRecommendationsLoadingState(recsLoadingModel)

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()
            assertThat(currentMedia)
                .containsExactly(MediaCommonModel.MediaRecommendations(recsLoadingModel))

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)
            mediaFilterRepository.setOrderedMedia()

            assertThat(hasActiveMediaOrRecommendation).isTrue()
            assertThat(hasAnyMediaOrRecommendation).isTrue()
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaRecommendations(recsLoadingModel),
                    MediaCommonModel.MediaControl(mediaLoadingModel, true)
                )
                .inOrder()

            underTest.logSmartspaceSeenCard(0, 1, false)

            verify(smartspaceLogger)
                .logSmartspaceCardUIEvent(
                    MediaSmartspaceLogger.SMARTSPACE_CARD_SEEN_EVENT,
                    SmallHash.hash(mediaRecommendation.targetId),
                    Process.INVALID_UID,
                    surface = SURFACE,
                    2,
                    true
                )
        }

    @Test
    fun addActiveRecommendation_thenInactive() =
        testScope.runTest {
            val hasActiveMediaOrRecommendation by
                collectLastValue(underTest.hasActiveMediaOrRecommendation)
            val hasAnyMediaOrRecommendation by
                collectLastValue(underTest.hasAnyMediaOrRecommendation)
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, false)

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
        testScope.runTest { assertThat(underTest.hasAnyMedia()).isFalse() }

    @Test
    fun hasAnyMediaOrRecommendation_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasAnyMediaOrRecommendation.value).isFalse() }

    @Test
    fun hasActiveMedia_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasActiveMedia()).isFalse() }

    @Test
    fun hasActiveMediaOrRecommendation_nothingSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasActiveMediaOrRecommendation.value).isFalse() }

    @Test
    fun loadMediaFromRec() =
        testScope.runTest {
            val currentMedia by collectLastValue(underTest.currentMedia)
            val instanceId = InstanceId.fakeInstanceId(123)
            val data =
                MediaData(
                    active = true,
                    instanceId = instanceId,
                    packageName = PACKAGE_NAME,
                    notificationKey = KEY
                )
            val smartspaceLoadingModel = SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE)
            val mediaLoadingModel = MediaDataLoadingModel.Loaded(instanceId)

            mediaFilterRepository.setRecommendation(mediaRecommendation)
            mediaFilterRepository.setRecommendationsLoadingState(smartspaceLoadingModel)
            mediaRecommendationsInteractor.switchToMediaControl(PACKAGE_NAME)
            mediaFilterRepository.addSelectedUserMediaEntry(data)
            mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)

            assertThat(currentMedia)
                .containsExactly(MediaCommonModel.MediaRecommendations(smartspaceLoadingModel))
                .inOrder()

            mediaFilterRepository.addSelectedUserMediaEntry(data.copy(isPlaying = true))
            mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)

            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(mediaLoadingModel, isMediaFromRec = true),
                    MediaCommonModel.MediaRecommendations(smartspaceLoadingModel)
                )
                .inOrder()
        }

    @Test
    fun loadMediaAndRecommendation_logSmartspaceSeenCard() {
        val instanceId = InstanceId.fakeInstanceId(123)
        val data =
            MediaData(
                active = true,
                instanceId = instanceId,
                packageName = PACKAGE_NAME,
                notificationKey = KEY
            )
        val smartspaceLoadingModel = SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE)
        val mediaLoadingModel = MediaDataLoadingModel.Loaded(instanceId)

        mediaFilterRepository.addSelectedUserMediaEntry(data)
        mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)
        underTest.logSmartspaceSeenCard(0, 1, false)

        verify(smartspaceLogger)
            .logSmartspaceCardUIEvent(
                MediaSmartspaceLogger.SMARTSPACE_CARD_SEEN_EVENT,
                data.smartspaceId,
                data.appUid,
                surface = SURFACE,
                1
            )

        reset(smartspaceLogger)
        mediaFilterRepository.addSelectedUserMediaEntry(data)
        mediaFilterRepository.addMediaDataLoadingState(mediaLoadingModel)
        underTest.logSmartspaceSeenCard(0, 1, true)

        verify(smartspaceLogger, never())
            .logSmartspaceCardUIEvent(
                MediaSmartspaceLogger.SMARTSPACE_CARD_SEEN_EVENT,
                data.smartspaceId,
                data.appUid,
                surface = SURFACE,
                2
            )

        reset(smartspaceLogger)
        mediaFilterRepository.setRecommendation(mediaRecommendation)
        mediaFilterRepository.setRecommendationsLoadingState(smartspaceLoadingModel)
        underTest.logSmartspaceSeenCard(1, 1, true)

        verify(smartspaceLogger)
            .logSmartspaceCardUIEvent(
                MediaSmartspaceLogger.SMARTSPACE_CARD_SEEN_EVENT,
                SmallHash.hash(mediaRecommendation.targetId),
                Process.INVALID_UID,
                surface = SURFACE,
                2,
                true,
                rank = 1
            )

        reset(smartspaceLogger)
        mediaFilterRepository.addSelectedUserMediaEntry(data)
        mediaFilterRepository.addMediaDataLoadingState(
            mediaLoadingModel.copy(receivedSmartspaceCardLatency = 1)
        )
        underTest.logSmartspaceSeenCard(0, 1, true)

        verify(smartspaceLogger)
            .logSmartspaceCardUIEvent(
                MediaSmartspaceLogger.SMARTSPACE_CARD_SEEN_EVENT,
                data.smartspaceId,
                data.appUid,
                surface = SURFACE,
                2
            )
    }

    companion object {
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val PACKAGE_NAME = "com.android.example"
        private const val KEY = "key"
        private const val SURFACE = 4
    }
}
