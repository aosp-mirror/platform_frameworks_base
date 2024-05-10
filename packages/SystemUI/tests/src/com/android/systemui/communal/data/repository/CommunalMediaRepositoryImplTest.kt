/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalMediaRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var mediaData: MediaData

    private val mediaDataListenerCaptor: KotlinArgumentCaptor<MediaDataManager.Listener> by lazy {
        KotlinArgumentCaptor(MediaDataManager.Listener::class.java)
    }

    private lateinit var mediaRepository: CommunalMediaRepository

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun mediaPlaying_defaultsToFalse() =
        testScope.runTest {
            mediaRepository = CommunalMediaRepositoryImpl(mediaDataManager)

            val isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isFalse()
        }

    @Test
    fun mediaPlaying_emitsInitialValue() =
        testScope.runTest {
            // Start with media available.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(true)

            mediaRepository = CommunalMediaRepositoryImpl(mediaDataManager)

            val isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isTrue()
        }

    @Test
    fun mediaPlaying_updatesWhenMediaDataLoaded() =
        testScope.runTest {
            mediaRepository = CommunalMediaRepositoryImpl(mediaDataManager)

            // Initial value is false.
            var isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isFalse()

            // Listener is added
            verify(mediaDataManager).addListener(mediaDataListenerCaptor.capture())

            // Change to media available and notify the listener.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(true)
            mediaDataListenerCaptor.value.onMediaDataLoaded("key", null, mediaData)

            // mediaPlaying now returns true.
            isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isTrue()
        }

    @Test
    fun mediaPlaying_updatesWhenMediaDataRemoved() =
        testScope.runTest {
            // Start with media available.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(true)

            mediaRepository = CommunalMediaRepositoryImpl(mediaDataManager)

            // Initial value is true.
            var isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isTrue()

            // Listener is added.
            verify(mediaDataManager).addListener(mediaDataListenerCaptor.capture())

            // Change to media unavailable and notify the listener.
            whenever(mediaDataManager.hasAnyMediaOrRecommendation()).thenReturn(false)
            mediaDataListenerCaptor.value.onMediaDataLoaded("key", null, mediaData)

            // mediaPlaying now returns false.
            isMediaPlaying = collectLastValue(mediaRepository.mediaPlaying)
            runCurrent()
            assertThat(isMediaPlaying()).isFalse()
        }
}
