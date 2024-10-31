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
import com.android.systemui.recordissue.RecordIssueModule.Companion.TILE_SPEC
import com.android.traceur.PresetTraceConfigs
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

/**
 * CustomTraceState is customized Traceur settings for power users. These settings determine what
 * tracing is used during the Record Issue Quick Settings flow. This class tests that those features
 * are persistently and accurately stored across sessions.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class CustomTraceStateTest : SysuiTestCase() {

    private lateinit var underTest: CustomTraceState

    @Before
    fun setup() {
        underTest = CustomTraceState(context.getSharedPreferences(TILE_SPEC, 0))
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun trace_config_is_stored_accurately() {
        val expected = PresetTraceConfigs.getUiConfig()

        underTest.traceConfig = expected

        val actual = underTest.traceConfig
        Truth.assertThat(actual.longTrace).isEqualTo(expected.longTrace)
        Truth.assertThat(actual.maxLongTraceSizeMb).isEqualTo(expected.maxLongTraceSizeMb)
        Truth.assertThat(actual.maxLongTraceDurationMinutes)
            .isEqualTo(expected.maxLongTraceDurationMinutes)
        Truth.assertThat(actual.apps).isEqualTo(expected.apps)
        Truth.assertThat(actual.winscope).isEqualTo(expected.winscope)
        Truth.assertThat(actual.attachToBugreport).isEqualTo(expected.attachToBugreport)
        Truth.assertThat(actual.bufferSizeKb).isEqualTo(expected.bufferSizeKb)
        Truth.assertThat(actual.tags).isEqualTo(expected.tags)
    }

    @Test
    fun trace_config_is_persistently_stored_between_instances() {
        val expected = PresetTraceConfigs.getUiConfig()

        underTest.traceConfig = expected

        val actual = CustomTraceState(context.getSharedPreferences(TILE_SPEC, 0)).traceConfig
        Truth.assertThat(actual.longTrace).isEqualTo(expected.longTrace)
        Truth.assertThat(actual.maxLongTraceSizeMb).isEqualTo(expected.maxLongTraceSizeMb)
        Truth.assertThat(actual.maxLongTraceDurationMinutes)
            .isEqualTo(expected.maxLongTraceDurationMinutes)
        Truth.assertThat(actual.apps).isEqualTo(expected.apps)
        Truth.assertThat(actual.winscope).isEqualTo(expected.winscope)
        Truth.assertThat(actual.attachToBugreport).isEqualTo(expected.attachToBugreport)
        Truth.assertThat(actual.bufferSizeKb).isEqualTo(expected.bufferSizeKb)
        Truth.assertThat(actual.tags).isEqualTo(expected.tags)
    }
}
