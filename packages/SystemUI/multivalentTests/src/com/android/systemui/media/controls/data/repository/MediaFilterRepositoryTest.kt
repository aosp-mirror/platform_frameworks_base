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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaFilterRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest: MediaFilterRepository = kosmos.mediaFilterRepository

    @Test
    fun addSelectedUserMediaEntry_activeThenInactivate() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val userMedia = MediaData().copy(active = true)

            underTest.addSelectedUserMediaEntry(KEY, userMedia)

            assertThat(selectedUserEntries?.get(KEY)).isEqualTo(userMedia)

            underTest.addSelectedUserMediaEntry(KEY, userMedia.copy(active = false))

            assertThat(selectedUserEntries?.get(KEY)).isNotEqualTo(userMedia)
            assertThat(selectedUserEntries?.get(KEY)?.active).isFalse()
        }

    @Test
    fun addSelectedUserMediaEntry_thenRemove_returnsBoolean() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val userMedia = MediaData()

            underTest.addSelectedUserMediaEntry(KEY, userMedia)

            assertThat(selectedUserEntries?.get(KEY)).isEqualTo(userMedia)

            assertThat(underTest.removeSelectedUserMediaEntry(KEY, userMedia)).isTrue()
        }

    @Test
    fun addSelectedUserMediaEntry_thenRemove_returnsValue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(underTest.selectedUserEntries)

            val userMedia = MediaData()

            underTest.addSelectedUserMediaEntry(KEY, userMedia)

            assertThat(selectedUserEntries?.get(KEY)).isEqualTo(userMedia)

            assertThat(underTest.removeSelectedUserMediaEntry(KEY)).isEqualTo(userMedia)
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

            val mediaRecommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(context),
                )

            underTest.setRecommendation(mediaRecommendation)

            assertThat(smartspaceMediaData).isEqualTo(mediaRecommendation)

            underTest.setRecommendation(mediaRecommendation.copy(isActive = false))

            assertThat(smartspaceMediaData).isNotEqualTo(mediaRecommendation)
            assertThat(smartspaceMediaData?.isActive).isFalse()
        }

    companion object {
        private const val KEY = "KEY"
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
    }
}
