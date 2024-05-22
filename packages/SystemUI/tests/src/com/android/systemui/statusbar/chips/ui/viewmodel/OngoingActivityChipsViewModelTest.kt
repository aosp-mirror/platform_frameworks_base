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

package com.android.systemui.statusbar.chips.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
class OngoingActivityChipsViewModelTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val underTest = kosmos.ongoingActivityChipsViewModel

    @Test
    fun chip_allHidden_hidden() =
        kosmos.testScope.runTest {
            kosmos.screenRecordChipInteractor.chip.value = OngoingActivityChipModel.Hidden
            kosmos.callChipInteractor.chip.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden)
        }

    @Test
    fun chip_screenRecordShow_restHidden_screenRecordShown() =
        kosmos.testScope.runTest {
            val screenRecordChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("icon")),
                    startTimeMs = 500L,
                ) {}
            kosmos.screenRecordChipInteractor.chip.value = screenRecordChip
            kosmos.callChipInteractor.chip.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(screenRecordChip)
        }

    @Test
    fun chip_screenRecordShowAndCallShow_screenRecordShown() =
        kosmos.testScope.runTest {
            val screenRecordChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("icon")),
                    startTimeMs = 500L,
                ) {}
            kosmos.screenRecordChipInteractor.chip.value = screenRecordChip

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            kosmos.callChipInteractor.chip.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(screenRecordChip)
        }

    @Test
    fun chip_screenRecordHideAndCallShown_callShown() =
        kosmos.testScope.runTest {
            kosmos.screenRecordChipInteractor.chip.value = OngoingActivityChipModel.Hidden

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            kosmos.callChipInteractor.chip.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(callChip)
        }

    @Test
    fun chip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        kosmos.testScope.runTest {
            // Start with just the lower priority call chip
            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            kosmos.callChipInteractor.chip.value = callChip
            kosmos.screenRecordChipInteractor.chip.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(callChip)

            // WHEN the higher priority screen record chip is added
            val screenRecordChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("icon")),
                    startTimeMs = 500L,
                ) {}
            kosmos.screenRecordChipInteractor.chip.value = screenRecordChip

            // THEN the higher priority screen record chip is used
            assertThat(latest).isEqualTo(screenRecordChip)
        }

    @Test
    fun chip_highestPriorityChipRemoved_showsNextPriorityChip() =
        kosmos.testScope.runTest {
            // Start with both the higher priority screen record chip and lower priority call chip
            val screenRecordChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_cake, ContentDescription.Loaded("icon")),
                    startTimeMs = 500L,
                ) {}
            kosmos.screenRecordChipInteractor.chip.value = screenRecordChip

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            kosmos.callChipInteractor.chip.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(screenRecordChip)

            // WHEN the higher priority screen record is removed
            kosmos.screenRecordChipInteractor.chip.value = OngoingActivityChipModel.Hidden

            // THEN the lower priority call is used
            assertThat(latest).isEqualTo(callChip)
        }
}
