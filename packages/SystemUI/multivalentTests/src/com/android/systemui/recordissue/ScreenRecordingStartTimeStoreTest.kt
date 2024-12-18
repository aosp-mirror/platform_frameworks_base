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

package com.android.systemui.recordissue

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class ScreenRecordingStartTimeStoreTest : SysuiTestCase() {
    private val userTracker: UserTracker = Kosmos().also { it.testCase = this }.userTracker

    private lateinit var underTest: ScreenRecordingStartTimeStore

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = ScreenRecordingStartTimeStore(userTracker)
    }

    @Test
    fun markStartTime_correctlyStoresValues_inSharedPreferences() {
        underTest.markStartTime()

        val startTimeMetadata = underTest.userIdToScreenRecordingStartTime.get(userTracker.userId)
        Truth.assertThat(startTimeMetadata).isNotNull()
        Truth.assertThat(startTimeMetadata!!.getLong(ELAPSED_REAL_TIME_NANOS_KEY)).isNotNull()
        Truth.assertThat(startTimeMetadata.getLong(REAL_TO_ELAPSED_TIME_OFFSET_NANOS_KEY))
            .isNotNull()
    }
}
