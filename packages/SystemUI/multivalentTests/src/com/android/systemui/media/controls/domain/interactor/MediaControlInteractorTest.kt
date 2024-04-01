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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaControlInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaControlInteractor
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.util.mediaInstanceId
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaControlInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl = kosmos.mediaDataFilter
    private val instanceId: InstanceId = kosmos.mediaInstanceId
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager

    private val underTest: MediaControlInteractor = kosmos.mediaControlInteractor

    @Test
    fun onMediaDataUpdated() =
        testScope.runTest {
            whenever(notificationLockscreenUserManager.isCurrentProfile(USER_ID)).thenReturn(true)
            whenever(notificationLockscreenUserManager.isProfileAvailable(USER_ID)).thenReturn(true)
            val controlModel by collectLastValue(underTest.mediaControl)
            var mediaData =
                MediaData(userId = USER_ID, instanceId = instanceId, artist = SESSION_ARTIST)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isEqualTo(instanceId)
            assertThat(controlModel?.artistName).isEqualTo(SESSION_ARTIST)

            mediaData =
                MediaData(userId = USER_ID, instanceId = instanceId, artist = SESSION_ARTIST_2)

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isEqualTo(instanceId)
            assertThat(controlModel?.artistName).isEqualTo(SESSION_ARTIST_2)

            mediaData =
                MediaData(
                    userId = USER_ID,
                    instanceId = InstanceId.fakeInstanceId(2),
                    artist = SESSION_ARTIST
                )

            mediaDataFilter.onMediaDataLoaded(KEY, KEY, mediaData)

            assertThat(controlModel?.instanceId).isNotEqualTo(mediaData.instanceId)
            assertThat(controlModel?.artistName).isEqualTo(SESSION_ARTIST_2)
        }

    companion object {
        private const val USER_ID = 0
        private const val KEY = "key"
        private const val SESSION_ARTIST = "artist"
        private const val SESSION_ARTIST_2 = "artist2"
    }
}
