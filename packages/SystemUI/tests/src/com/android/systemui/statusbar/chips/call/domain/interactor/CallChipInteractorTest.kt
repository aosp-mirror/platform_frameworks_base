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

package com.android.systemui.statusbar.chips.call.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

@SmallTest
class CallChipInteractorTest : SysuiTestCase() {
    val kosmos = Kosmos()
    val repo = kosmos.ongoingCallRepository

    val underTest = kosmos.callChipInteractor

    @Test
    fun ongoingCallState_matchesRepo() =
        kosmos.testScope.runTest {
            val latest by collectLastValue(underTest.ongoingCallState)

            val inCall = OngoingCallModel.InCall(startTimeMs = 1000, intent = null)
            repo.setOngoingCallState(inCall)
            assertThat(latest).isEqualTo(inCall)

            val noCall = OngoingCallModel.NoCall
            repo.setOngoingCallState(noCall)
            assertThat(latest).isEqualTo(noCall)
        }
}
