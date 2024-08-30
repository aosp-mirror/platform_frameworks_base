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

import androidx.annotation.DrawableRes
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class ChipTransitionHelperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    @Test
    fun createChipFlow_typicallyFollowsInputFlow() =
        testScope.runTest {
            val underTest = ChipTransitionHelper(kosmos.applicationCoroutineScope)
            val inputChipFlow =
                MutableStateFlow<OngoingActivityChipModel>(OngoingActivityChipModel.Hidden())
            val latest by collectLastValue(underTest.createChipFlow(inputChipFlow))

            val newChip =
                OngoingActivityChipModel.Shown.Timer(
                    icon = createIcon(R.drawable.ic_cake),
                    colors = ColorsModel.Themed,
                    startTimeMs = 100L,
                    onClickListener = null,
                )

            inputChipFlow.value = newChip

            assertThat(latest).isEqualTo(newChip)

            val newerChip =
                OngoingActivityChipModel.Shown.IconOnly(
                    icon = createIcon(R.drawable.ic_hotspot),
                    colors = ColorsModel.Themed,
                    onClickListener = null,
                )

            inputChipFlow.value = newerChip

            assertThat(latest).isEqualTo(newerChip)
        }

    @Test
    fun activityStopped_chipHiddenWithoutAnimationFor500ms() =
        testScope.runTest {
            val underTest = ChipTransitionHelper(kosmos.applicationCoroutineScope)
            val inputChipFlow =
                MutableStateFlow<OngoingActivityChipModel>(OngoingActivityChipModel.Hidden())
            val latest by collectLastValue(underTest.createChipFlow(inputChipFlow))

            val shownChip =
                OngoingActivityChipModel.Shown.Timer(
                    icon = createIcon(R.drawable.ic_cake),
                    colors = ColorsModel.Themed,
                    startTimeMs = 100L,
                    onClickListener = null,
                )

            inputChipFlow.value = shownChip

            assertThat(latest).isEqualTo(shownChip)

            // WHEN #onActivityStopped is invoked
            underTest.onActivityStoppedFromDialog()
            runCurrent()

            // THEN the chip is hidden and has no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))

            // WHEN only 250ms have elapsed
            advanceTimeBy(250)

            // THEN the chip is still hidden
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))

            // WHEN over 500ms have elapsed
            advanceTimeBy(251)

            // THEN the chip returns to the original input flow value
            assertThat(latest).isEqualTo(shownChip)
        }

    @Test
    fun activityStopped_stoppedAgainBefore500ms_chipReshownAfterSecond500ms() =
        testScope.runTest {
            val underTest = ChipTransitionHelper(kosmos.applicationCoroutineScope)
            val inputChipFlow =
                MutableStateFlow<OngoingActivityChipModel>(OngoingActivityChipModel.Hidden())
            val latest by collectLastValue(underTest.createChipFlow(inputChipFlow))

            val shownChip =
                OngoingActivityChipModel.Shown.Timer(
                    icon = createIcon(R.drawable.ic_cake),
                    colors = ColorsModel.Themed,
                    startTimeMs = 100L,
                    onClickListener = null,
                )

            inputChipFlow.value = shownChip

            assertThat(latest).isEqualTo(shownChip)

            // WHEN #onActivityStopped is invoked
            underTest.onActivityStoppedFromDialog()
            runCurrent()

            // THEN the chip is hidden and has no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))

            // WHEN 250ms have elapsed, get another stop event
            advanceTimeBy(250)
            underTest.onActivityStoppedFromDialog()
            runCurrent()

            // THEN the chip is still hidden for another 500ms afterwards
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))
            advanceTimeBy(499)
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))
            advanceTimeBy(2)
            assertThat(latest).isEqualTo(shownChip)
        }

    private fun createIcon(@DrawableRes drawable: Int) =
        OngoingActivityChipModel.ChipIcon.SingleColorIcon(
            Icon.Resource(drawable, contentDescription = null)
        )
}
