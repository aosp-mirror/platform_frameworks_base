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

package com.android.systemui.qs.tiles.impl.irecording

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.recordissue.IssueRecordingState
import com.android.systemui.settings.fakeUserFileManager
import com.android.systemui.settings.userTracker
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class IssueRecordingDataInteractorTest : SysuiTestCase() {

    private val kosmos = Kosmos().also { it.testCase = this }
    private val userTracker = kosmos.userTracker
    private val userFileManager = kosmos.fakeUserFileManager
    private val testUser = UserHandle.of(1)

    lateinit var state: IssueRecordingState
    private lateinit var underTest: IssueRecordingDataInteractor

    @Before
    fun setup() {
        state = IssueRecordingState(userTracker, userFileManager)
        underTest = IssueRecordingDataInteractor(state, kosmos.testScope.testScheduler)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun emitsEvent_whenIsRecordingStatusChanges_correctly() {
        kosmos.testScope.runTest {
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            Truth.assertThat(data?.isRecording).isFalse()

            state.isRecording = true
            runCurrent()
            Truth.assertThat(data?.isRecording).isTrue()

            state.isRecording = false
            runCurrent()
            Truth.assertThat(data?.isRecording).isFalse()
        }
    }
}
