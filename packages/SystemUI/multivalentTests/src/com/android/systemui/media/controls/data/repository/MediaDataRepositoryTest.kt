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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
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
class MediaDataRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest: MediaDataRepository = kosmos.mediaDataRepository

    @Test
    fun setRecommendation() =
        testScope.runTest {
            val smartspaceData by collectLastValue(underTest.smartspaceMediaData)
            val recommendation = SmartspaceMediaData(isActive = true)

            underTest.setRecommendation(recommendation)

            assertThat(smartspaceData).isEqualTo(recommendation)
        }

    @Test
    fun addAndRemoveMediaData() =
        testScope.runTest {
            val entries by collectLastValue(underTest.mediaEntries)

            val firstKey = "key1"
            val firstData = MediaData().copy(isPlaying = true)

            val secondKey = "key2"
            val secondData = MediaData().copy(resumption = true)

            underTest.addMediaEntry(firstKey, firstData)
            underTest.addMediaEntry(secondKey, secondData)
            underTest.addMediaEntry(firstKey, firstData.copy(isPlaying = false))

            assertThat(entries!!.size).isEqualTo(2)
            assertThat(entries!![firstKey]).isNotEqualTo(firstData)

            underTest.removeMediaEntry(firstKey)

            assertThat(entries!!.size).isEqualTo(1)
            assertThat(entries!![secondKey]).isEqualTo(secondData)
        }

    @Test
    fun setRecommendationInactive() =
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.MEDIA_RETAIN_RECOMMENDATIONS, true)
            val smartspaceData by collectLastValue(underTest.smartspaceMediaData)
            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val recommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )

            underTest.setRecommendation(recommendation)

            assertThat(smartspaceData).isEqualTo(recommendation)

            underTest.setRecommendationInactive(KEY_MEDIA_SMARTSPACE)

            assertThat(smartspaceData).isNotEqualTo(recommendation)
            assertThat(smartspaceData!!.isActive).isFalse()
        }

    @Test
    fun dismissRecommendation() =
        testScope.runTest {
            val smartspaceData by collectLastValue(underTest.smartspaceMediaData)
            val icon = Icon.createWithResource(context, R.drawable.ic_media_play)
            val recommendation =
                SmartspaceMediaData(
                    targetId = KEY_MEDIA_SMARTSPACE,
                    isActive = true,
                    recommendations = MediaTestHelper.getValidRecommendationList(icon),
                )

            underTest.setRecommendation(recommendation)

            assertThat(smartspaceData).isEqualTo(recommendation)

            underTest.dismissSmartspaceRecommendation(KEY_MEDIA_SMARTSPACE)

            assertThat(smartspaceData!!.isActive).isFalse()
        }

    companion object {
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
    }
}
