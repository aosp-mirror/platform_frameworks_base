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

package com.android.systemui.statusbar.featurepods.media.domain.interactor

import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaControlChipInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val underTest = kosmos.mediaControlChipInteractor

    @Test
    fun mediaControlModel_noActiveMedia_null() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            assertThat(model).isNull()
        }

    @Test
    fun mediaControlModel_activeMedia_notNull() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            val userMedia = MediaData(active = true)
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(model).isNotNull()
        }

    @Test
    fun mediaControlModel_mediaRemoved_null() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            val userMedia = MediaData(active = true)
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(model).isNotNull()

            assertThat(mediaFilterRepository.removeSelectedUserMediaEntry(instanceId, userMedia))
                .isTrue()
            mediaFilterRepository.addMediaDataLoadingState(
                MediaDataLoadingModel.Removed(instanceId)
            )

            assertThat(model).isNull()
        }

    @Test
    fun mediaControlModel_songNameChanged_emitsUpdatedModel() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            val initialSongName = "Initial Song"
            val newSongName = "New Song"
            val userMedia = MediaData(active = true, song = initialSongName)
            val instanceId = userMedia.instanceId

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(model).isNotNull()
            assertThat(model?.songName).isEqualTo(initialSongName)

            val updatedUserMedia = userMedia.copy(song = newSongName)
            mediaFilterRepository.addSelectedUserMediaEntry(updatedUserMedia)

            assertThat(model?.songName).isEqualTo(newSongName)
        }

    @Test
    fun mediaControlModel_playPauseActionChanges_emitsUpdatedModel() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            val mockDrawable = mock<Drawable>()

            val initialAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "Initial Action",
                    background = mockDrawable,
                )
            val mediaButton = MediaButton(playOrPause = initialAction)
            val userMedia = MediaData(active = true, semanticActions = mediaButton)
            val instanceId = userMedia.instanceId
            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(model).isNotNull()
            assertThat(model?.playOrPause).isEqualTo(initialAction)

            val newAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "New Action",
                    background = mockDrawable,
                )
            val updatedMediaButton = MediaButton(playOrPause = newAction)
            val updatedUserMedia = userMedia.copy(semanticActions = updatedMediaButton)
            mediaFilterRepository.addSelectedUserMediaEntry(updatedUserMedia)

            assertThat(model?.playOrPause).isEqualTo(newAction)
        }

    @Test
    fun mediaControlModel_playPauseActionRemoved_playPauseNull() =
        kosmos.runTest {
            val model by collectLastValue(underTest.mediaControlModel)

            val mockDrawable = mock<Drawable>()

            val initialAction =
                MediaAction(
                    icon = mockDrawable,
                    action = {},
                    contentDescription = "Initial Action",
                    background = mockDrawable,
                )
            val mediaButton = MediaButton(playOrPause = initialAction)
            val userMedia = MediaData(active = true, semanticActions = mediaButton)
            val instanceId = userMedia.instanceId
            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            mediaFilterRepository.addMediaDataLoadingState(MediaDataLoadingModel.Loaded(instanceId))

            assertThat(model).isNotNull()
            assertThat(model?.playOrPause).isEqualTo(initialAction)

            val updatedUserMedia = userMedia.copy(semanticActions = MediaButton())
            mediaFilterRepository.addSelectedUserMediaEntry(updatedUserMedia)

            assertThat(model?.playOrPause).isNull()
        }
}
