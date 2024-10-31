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

import android.content.ContentResolver
import android.os.Handler
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.settings.userFileManager
import com.android.systemui.settings.userTracker
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class IssueRecordingStateTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private lateinit var underTest: IssueRecordingState
    @Mock private lateinit var resolver: ContentResolver

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            IssueRecordingState(
                kosmos.userTracker,
                kosmos.userFileManager,
                Handler.getMain(),
                resolver,
                kosmos.fakeGlobalSettings,
            )
    }

    @Test
    fun takeBugreport_isSaved_betweenDifferentSessions() {
        val expected = true

        underTest.takeBugreport = expected
        underTest =
            IssueRecordingState(
                kosmos.userTracker,
                kosmos.userFileManager,
                Handler.getMain(),
                resolver,
                kosmos.fakeGlobalSettings,
            )

        Truth.assertThat(underTest.takeBugreport).isEqualTo(expected)
    }

    @Test
    fun recordScreen_isSaved_betweenDifferentSessions() {
        val expected = true

        underTest.recordScreen = expected
        underTest =
            IssueRecordingState(
                kosmos.userTracker,
                kosmos.userFileManager,
                Handler.getMain(),
                resolver,
                kosmos.fakeGlobalSettings,
            )

        Truth.assertThat(underTest.recordScreen).isEqualTo(expected)
    }

    @Test
    fun hasUserApprovedScreenRecording_isTrue_afterBeingMarkedAsCompleted() {
        underTest.markUserApprovalForScreenRecording()
        underTest =
            IssueRecordingState(
                kosmos.userTracker,
                kosmos.userFileManager,
                Handler.getMain(),
                resolver,
                kosmos.fakeGlobalSettings,
            )

        Truth.assertThat(underTest.hasUserApprovedScreenRecording).isEqualTo(true)
    }

    @Test
    fun tagTitles_areSavedConsistently() {
        val expected = setOf("a", "b", "c")

        underTest.tagTitles = expected
        underTest =
            IssueRecordingState(
                kosmos.userTracker,
                kosmos.userFileManager,
                Handler.getMain(),
                resolver,
                kosmos.fakeGlobalSettings,
            )

        Truth.assertThat(underTest.tagTitles).isEqualTo(expected)
    }

    @Test
    fun addListener_registersContentObserver_ifListOfListenersIsNotEmpty() {
        val listener = Runnable { /* No-op */ }

        underTest.addListener(listener)

        verify(resolver).registerContentObserver(any(), any(), any())
    }

    @Test
    fun removeListener_unRegistersContentObserver_ifListOfListenersIsEmpty() {
        val listener = Runnable { /* No-op */ }

        underTest.addListener(listener)
        underTest.removeListener(listener)

        verify(resolver).registerContentObserver(any(), any(), any())
        verify(resolver).unregisterContentObserver(any())
    }
}
