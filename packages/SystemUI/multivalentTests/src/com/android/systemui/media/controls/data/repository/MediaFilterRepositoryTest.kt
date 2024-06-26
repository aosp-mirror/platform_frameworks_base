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

package com.android.systemui.media.controls.data.repository

import android.R
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.media.controls.util.SmallHash
import com.android.systemui.media.controls.util.mediaSmartspaceLogger
import com.android.systemui.media.controls.util.mockMediaSmartspaceLogger
import com.android.systemui.testKosmos
import com.android.systemui.util.time.systemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaFilterRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val smartspaceLogger = kosmos.mockMediaSmartspaceLogger
    private val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val mediaRecommendation =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )

    private val underTest: MediaFilterRepository =
        with(kosmos) {
            mediaSmartspaceLogger = mockMediaSmartspaceLogger
            mediaFilterRepository
        }

    @Test
    fun addSelectedUserMediaEntry_activeThenInactivate() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val instanceId = InstanceId.fakeInstanceId(123)
            val userMedia = MediaData().copy(active = true, instanceId = instanceId)

            underTest.addSelectedUserMediaEntry(userMedia)

            assertThat(selectedUserEntries?.get(instanceId)).isEqualTo(userMedia)
            assertThat(underTest.hasActiveMedia()).isTrue()
            assertThat(underTest.hasAnyMedia()).isTrue()

            underTest.addSelectedUserMediaEntry(userMedia.copy(active = false))

            assertThat(selectedUserEntries?.get(instanceId)).isNotEqualTo(userMedia)
            assertThat(selectedUserEntries?.get(instanceId)?.active).isFalse()
            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isTrue()
        }

    @Test
    fun addSelectedUserMediaEntry_thenRemove_returnsBoolean() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val instanceId = InstanceId.fakeInstanceId(123)
            val userMedia = MediaData().copy(instanceId = instanceId)

            underTest.addSelectedUserMediaEntry(userMedia)

            assertThat(selectedUserEntries?.get(instanceId)).isEqualTo(userMedia)
            assertThat(underTest.hasActiveMedia()).isTrue()
            assertThat(underTest.hasAnyMedia()).isTrue()

            assertThat(underTest.removeSelectedUserMediaEntry(instanceId, userMedia)).isTrue()
            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isFalse()
        }

    @Test
    fun addSelectedUserMediaEntry_thenRemove_returnsValue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val instanceId = InstanceId.fakeInstanceId(123)
            val userMedia = MediaData().copy(instanceId = instanceId)

            underTest.addSelectedUserMediaEntry(userMedia)

            assertThat(selectedUserEntries?.get(instanceId)).isEqualTo(userMedia)

            assertThat(underTest.removeSelectedUserMediaEntry(instanceId)).isEqualTo(userMedia)
        }

    @Test
    fun addAllUserMediaEntry_activeThenInactivate() =
        testScope.runTest {
            val allUserEntries by collectLastValue(underTest.allUserEntries)

            val userMedia = MediaData().copy(active = true)

            underTest.addMediaEntry(KEY, userMedia)

            assertThat(allUserEntries?.get(KEY)).isEqualTo(userMedia)

            underTest.addMediaEntry(KEY, userMedia.copy(active = false))

            assertThat(allUserEntries?.get(KEY)).isNotEqualTo(userMedia)
            assertThat(allUserEntries?.get(KEY)?.active).isFalse()
        }

    @Test
    fun addAllUserMediaEntry_thenRemove_returnsValue() =
        testScope.runTest {
            val allUserEntries by collectLastValue(underTest.allUserEntries)

            val userMedia = MediaData()

            underTest.addMediaEntry(KEY, userMedia)

            assertThat(allUserEntries?.get(KEY)).isEqualTo(userMedia)

            assertThat(underTest.removeMediaEntry(KEY)).isEqualTo(userMedia)
        }

    @Test
    fun addActiveRecommendation_thenInactive() =
        testScope.runTest {
            val smartspaceMediaData by collectLastValue(underTest.smartspaceMediaData)

            underTest.setRecommendation(mediaRecommendation)

            assertThat(smartspaceMediaData).isEqualTo(mediaRecommendation)

            underTest.setRecommendation(mediaRecommendation.copy(isActive = false))

            assertThat(smartspaceMediaData).isNotEqualTo(mediaRecommendation)
            assertThat(underTest.isRecommendationActive()).isFalse()
        }

    @Test
    fun addMediaControlPlayingThenRemote() =
        testScope.runTest {
            val currentMedia by collectLastValue(underTest.currentMedia)
            val playingInstanceId = InstanceId.fakeInstanceId(123)
            val remoteInstanceId = InstanceId.fakeInstanceId(321)
            val playingData = createMediaData("app1", true, LOCAL, false, playingInstanceId)
            val remoteData = createMediaData("app2", true, REMOTE, false, remoteInstanceId)

            underTest.setRecommendation(mediaRecommendation)
            underTest.setRecommendationsLoadingState(
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            )
            underTest.addSelectedUserMediaEntry(playingData)
            underTest.addMediaDataLoadingState(
                MediaDataLoadingModel.Loaded(playingInstanceId),
                false
            )

            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    playingData.smartspaceId,
                    playingData.appUid,
                    cardinality = 2
                )

            underTest.addSelectedUserMediaEntry(remoteData)
            underTest.addMediaDataLoadingState(
                MediaDataLoadingModel.Loaded(remoteInstanceId),
                false
            )

            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    remoteData.smartspaceId,
                    playingData.appUid,
                    cardinality = 3,
                    rank = 1
                )
            assertThat(currentMedia?.size).isEqualTo(3)
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(playingInstanceId)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(remoteInstanceId)),
                    MediaCommonModel.MediaRecommendations(
                        SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
                    )
                )
                .inOrder()
        }

    @Test
    fun switchMediaControlsPlaying() =
        testScope.runTest {
            val currentMedia by collectLastValue(underTest.currentMedia)
            val playingInstanceId1 = InstanceId.fakeInstanceId(123)
            val playingInstanceId2 = InstanceId.fakeInstanceId(321)
            var playingData1 = createMediaData("app1", true, LOCAL, false, playingInstanceId1)
            var playingData2 = createMediaData("app2", false, LOCAL, false, playingInstanceId2)

            underTest.addSelectedUserMediaEntry(playingData1)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(playingInstanceId1))
            underTest.addSelectedUserMediaEntry(playingData2)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(playingInstanceId2))

            assertThat(currentMedia?.size).isEqualTo(2)
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(playingInstanceId1)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(playingInstanceId2))
                )
                .inOrder()

            playingData1 = createMediaData("app1", false, LOCAL, false, playingInstanceId1)
            playingData2 = createMediaData("app2", true, LOCAL, false, playingInstanceId2)

            underTest.addSelectedUserMediaEntry(playingData1)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(playingInstanceId1))
            underTest.addSelectedUserMediaEntry(playingData2)
            underTest.addMediaDataLoadingState(
                MediaDataLoadingModel.Loaded(playingInstanceId2, false)
            )

            assertThat(currentMedia?.size).isEqualTo(2)
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(playingInstanceId1)),
                    MediaCommonModel.MediaControl(
                        MediaDataLoadingModel.Loaded(playingInstanceId2, false)
                    )
                )
                .inOrder()

            underTest.setOrderedMedia()

            verify(smartspaceLogger, never())
                .logSmartspaceCardReceived(
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyBoolean(),
                    anyBoolean(),
                    anyInt(),
                    anyInt()
                )
            assertThat(currentMedia?.size).isEqualTo(2)
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(
                        MediaDataLoadingModel.Loaded(playingInstanceId2, false)
                    ),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(playingInstanceId1))
                )
                .inOrder()
        }

    @Test
    fun fullOrderTest() =
        testScope.runTest {
            val currentMedia by collectLastValue(underTest.currentMedia)
            val instanceId1 = InstanceId.fakeInstanceId(123)
            val instanceId2 = InstanceId.fakeInstanceId(456)
            val instanceId3 = InstanceId.fakeInstanceId(321)
            val instanceId4 = InstanceId.fakeInstanceId(654)
            val instanceId5 = InstanceId.fakeInstanceId(124)
            val playingAndLocalData = createMediaData("app1", true, LOCAL, false, instanceId1)
            val playingAndRemoteData = createMediaData("app2", true, REMOTE, false, instanceId2)
            val stoppedAndLocalData = createMediaData("app3", false, LOCAL, false, instanceId3)
            val stoppedAndRemoteData = createMediaData("app4", false, REMOTE, false, instanceId4)
            val canResumeData = createMediaData("app5", false, LOCAL, true, instanceId5)

            underTest.addSelectedUserMediaEntry(stoppedAndLocalData)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId3))

            underTest.addSelectedUserMediaEntry(stoppedAndRemoteData)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId4))

            underTest.addSelectedUserMediaEntry(canResumeData)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId5))

            underTest.addSelectedUserMediaEntry(playingAndLocalData)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId1))

            underTest.addSelectedUserMediaEntry(playingAndRemoteData)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId2))

            underTest.setRecommendation(mediaRecommendation)
            underTest.setRecommendationsLoadingState(
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            )
            underTest.setOrderedMedia()

            val smartspaceId = SmallHash.hash(mediaRecommendation.targetId)
            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    eq(smartspaceId),
                    anyInt(),
                    eq(6),
                    anyBoolean(),
                    anyBoolean(),
                    eq(2),
                    anyInt()
                )
            verify(smartspaceLogger, never())
                .logSmartspaceCardReceived(
                    eq(playingAndLocalData.smartspaceId),
                    anyInt(),
                    anyInt(),
                    anyBoolean(),
                    anyBoolean(),
                    anyInt(),
                    anyInt()
                )
            assertThat(currentMedia?.size).isEqualTo(6)
            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId1)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId2)),
                    MediaCommonModel.MediaRecommendations(
                        SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
                    ),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId4)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId3)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId5)),
                )
                .inOrder()
        }

    @Test
    fun loadMediaFromRec() =
        testScope.runTest {
            val currentMedia by collectLastValue(underTest.currentMedia)
            val instanceId1 = InstanceId.fakeInstanceId(123)
            val instanceId2 = InstanceId.fakeInstanceId(456)
            val data =
                MediaData(
                    active = true,
                    instanceId = instanceId1,
                    packageName = PACKAGE_NAME,
                    isPlaying = true,
                    notificationKey = KEY,
                )
            val newData =
                MediaData(
                    active = true,
                    instanceId = instanceId2,
                    isPlaying = true,
                    notificationKey = KEY_2
                )

            underTest.setMediaFromRecPackageName(PACKAGE_NAME)
            underTest.addSelectedUserMediaEntry(data)
            underTest.setRecommendation(mediaRecommendation)
            underTest.setRecommendationsLoadingState(
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE)
            )
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId1))

            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(
                        MediaDataLoadingModel.Loaded(instanceId1),
                        isMediaFromRec = true
                    ),
                    MediaCommonModel.MediaRecommendations(
                        SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE)
                    )
                )
                .inOrder()

            underTest.addSelectedUserMediaEntry(newData)
            underTest.addSelectedUserMediaEntry(data.copy(isPlaying = false))
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId2))
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId1))

            assertThat(currentMedia)
                .containsExactly(
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId2)),
                    MediaCommonModel.MediaControl(MediaDataLoadingModel.Loaded(instanceId1)),
                    MediaCommonModel.MediaRecommendations(
                        SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE)
                    )
                )
                .inOrder()
        }

    @Test
    fun hasAnyMedia_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasAnyMedia()).isFalse() }

    @Test
    fun hasActiveMedia_noMediaSet_returnsFalse() =
        testScope.runTest { assertThat(underTest.hasActiveMedia()).isFalse() }

    @Test
    fun updateMediaWithLatency_smartspaceIsLogged() =
        testScope.runTest {
            val instanceId = InstanceId.fakeInstanceId(123)
            val data = createMediaData("app", true, LOCAL, false, instanceId)

            underTest.setRecommendation(mediaRecommendation)
            underTest.setRecommendationsLoadingState(
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            )

            val smartspaceId = SmallHash.hash(mediaRecommendation.targetId)
            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    eq(smartspaceId),
                    anyInt(),
                    eq(1),
                    eq(true),
                    anyBoolean(),
                    eq(0),
                    anyInt()
                )
            reset(smartspaceLogger)

            underTest.addSelectedUserMediaEntry(data)
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId), false)

            verify(smartspaceLogger)
                .logSmartspaceCardReceived(data.smartspaceId, data.appUid, cardinality = 2)

            reset(smartspaceLogger)

            underTest.addSelectedUserMediaEntry(data)
            underTest.addMediaDataLoadingState(
                MediaDataLoadingModel.Loaded(instanceId, receivedSmartspaceCardLatency = 123),
                true
            )

            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    SmallHash.hash(data.appUid + kosmos.systemClock.currentTimeMillis().toInt()),
                    data.appUid,
                    cardinality = 2,
                    rank = 0,
                    receivedLatencyMillis = 123
                )
        }

    @Test
    fun resumeMedia_loadSmartspace_allSmartspaceIsLogged() =
        testScope.runTest {
            val resumeInstanceId = InstanceId.fakeInstanceId(123)
            val data = createMediaData("app", false, LOCAL, true, resumeInstanceId)

            underTest.addSelectedUserMediaEntry(data.copy(active = false))
            underTest.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(resumeInstanceId))
            underTest.setRecommendation(mediaRecommendation)
            underTest.setRecommendationsLoadingState(
                SmartspaceMediaLoadingModel.Loaded(KEY_MEDIA_SMARTSPACE, true)
            )

            assertThat(underTest.hasActiveMedia()).isFalse()
            assertThat(underTest.hasAnyMedia()).isTrue()
            val smartspaceId = SmallHash.hash(mediaRecommendation.targetId)
            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    eq(smartspaceId),
                    anyInt(),
                    eq(2),
                    eq(true),
                    anyBoolean(),
                    eq(0),
                    anyInt()
                )
            verify(smartspaceLogger)
                .logSmartspaceCardReceived(
                    SmallHash.hash(data.appUid + kosmos.systemClock.currentTimeMillis().toInt()),
                    data.appUid,
                    cardinality = 2,
                    rank = 1
                )
        }

    private fun createMediaData(
        app: String,
        playing: Boolean,
        playbackLocation: Int,
        isResume: Boolean,
        instanceId: InstanceId,
    ): MediaData {
        return MediaData(
            playbackLocation = playbackLocation,
            resumption = isResume,
            notificationKey = "key: $app",
            isPlaying = playing,
            instanceId = instanceId
        )
    }

    companion object {
        private const val LOCAL = MediaData.PLAYBACK_LOCAL
        private const val REMOTE = MediaData.PLAYBACK_CAST_LOCAL
        private const val KEY = "KEY"
        private const val KEY_2 = "KEY_2"
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val PACKAGE_NAME = "com.android.example"
    }
}
