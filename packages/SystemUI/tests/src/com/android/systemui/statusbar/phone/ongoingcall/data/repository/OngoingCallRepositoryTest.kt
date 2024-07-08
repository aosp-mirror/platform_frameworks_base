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

package com.android.systemui.statusbar.phone.ongoingcall.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class OngoingCallRepositoryTest : SysuiTestCase() {
    private val underTest = OngoingCallRepository()

    @Test
    fun hasOngoingCall_matchesSet() {
        val inCallModel = OngoingCallModel.InCall(startTimeMs = 654, intent = null)
        underTest.setOngoingCallState(inCallModel)

        assertThat(underTest.ongoingCallState.value).isEqualTo(inCallModel)

        underTest.setOngoingCallState(OngoingCallModel.NoCall)

        assertThat(underTest.ongoingCallState.value).isEqualTo(OngoingCallModel.NoCall)
    }
}
