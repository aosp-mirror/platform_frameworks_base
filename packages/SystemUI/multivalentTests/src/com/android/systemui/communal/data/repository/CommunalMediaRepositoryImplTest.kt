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

package com.android.systemui.communal.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class CommunalMediaRepositoryImplTest : SysuiTestCase() {
    private val mediaDataManager = mock<MediaDataManager>()
    private val mediaData = mock<MediaData>()
    private val tableLogBuffer = mock<TableLogBuffer>()

    private lateinit var underTest: CommunalMediaRepositoryImpl

    private val mediaDataListenerCaptor = argumentCaptor<MediaDataManager.Listener>()

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Before
    fun setUp() {
        underTest =
            CommunalMediaRepositoryImpl(
                mediaDataManager,
                tableLogBuffer,
            )
    }

    @Test
    fun hasAnyMediaOrRecommendation_defaultsToFalse() =
        testScope.runTest {
            val mediaModel = collectLastValue(underTest.mediaModel)
            runCurrent()
            assertThat(mediaModel()?.hasAnyMediaOrRecommendation).isFalse()
        }

    @Test
    fun mediaModel_updatesWhenMediaDataLoaded() =
        testScope.runTest {
            underTest.startListening()

            // Listener is added
            verify(mediaDataManager).addListener(mediaDataListenerCaptor.capture())

            // Initial value is false.
            val mediaModel = collectLastValue(underTest.mediaModel)
            runCurrent()
            assertThat(mediaModel()?.hasAnyMediaOrRecommendation).isFalse()

            // Change to media available and notify the listener.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(true)
            whenever(mediaData.createdTimestampMillis).thenReturn(1234L)
            mediaDataListenerCaptor.firstValue.onMediaDataLoaded("key", null, mediaData)
            runCurrent()

            // Media active now returns true.
            assertThat(mediaModel()?.hasAnyMediaOrRecommendation).isTrue()
            assertThat(mediaModel()?.createdTimestampMillis).isEqualTo(1234L)
        }

    @Test
    fun mediaModel_updatesWhenMediaDataRemoved() =
        testScope.runTest {
            underTest.startListening()

            // Listener is added
            verify(mediaDataManager).addListener(mediaDataListenerCaptor.capture())

            // Change to media available and notify the listener.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(true)
            mediaDataListenerCaptor.firstValue.onMediaDataLoaded("key", null, mediaData)
            runCurrent()

            // Media active now returns true.
            val mediaModel = collectLastValue(underTest.mediaModel)
            assertThat(mediaModel()?.hasAnyMediaOrRecommendation).isTrue()

            // Change to media unavailable and notify the listener.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(false)
            mediaDataListenerCaptor.firstValue.onMediaDataRemoved("key", false)
            runCurrent()

            // Media active now returns false.
            assertThat(mediaModel()?.hasAnyMediaOrRecommendation).isFalse()
        }
}
