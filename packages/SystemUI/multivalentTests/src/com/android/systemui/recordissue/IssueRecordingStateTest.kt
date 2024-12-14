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
import com.android.systemui.settings.userFileManager
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class IssueRecordingStateTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private lateinit var underTest: IssueRecordingState

    @Before
    fun setup() {
        underTest = IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)
    }

    @Test
    fun takeBugreport_isSaved_betweenDifferentSessions() {
        val expected = true

        underTest.takeBugreport = expected
        underTest = IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)

        Truth.assertThat(underTest.takeBugreport).isEqualTo(expected)
    }

    @Test
    fun recordScreen_isSaved_betweenDifferentSessions() {
        val expected = true

        underTest.recordScreen = expected
        underTest = IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)

        Truth.assertThat(underTest.recordScreen).isEqualTo(expected)
    }

    @Test
    fun hasUserApprovedScreenRecording_isTrue_afterBeingMarkedAsCompleted() {
        underTest.markUserApprovalForScreenRecording()
        underTest = IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)

        Truth.assertThat(underTest.hasUserApprovedScreenRecording).isEqualTo(true)
    }

    @Test
    fun tagTitles_areSavedConsistently() {
        val expected = setOf("a", "b", "c")

        underTest.tagTitles = expected
        underTest = IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)

        Truth.assertThat(underTest.tagTitles).isEqualTo(expected)
    }

    @Test
    fun isRecording_callsListeners_onTheValueChanging() {
        val count = CountDownLatch(1)
        val listener = Runnable { count.countDown() }

        underTest.addListener(listener)
        underTest.isRecording = true

        Truth.assertThat(count.count).isEqualTo(0)
    }

    @Test
    fun isRecording_callsOnlyListeners_whoHaveNotBeenRemoved() {
        val count1 = CountDownLatch(1)
        val count2 = CountDownLatch(1)
        val listener1 = Runnable { count1.countDown() }
        val listener2 = Runnable { count2.countDown() }

        underTest.addListener(listener1)
        underTest.removeListener(listener1)
        underTest.addListener(listener2)
        underTest.isRecording = true

        Truth.assertThat(count1.count).isEqualTo(1)
        Truth.assertThat(count2.count).isEqualTo(0)
    }
}
