/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceSelectionManagerTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardQuickAffordanceSelectionManager

    @Before
    fun setUp() {
        underTest = KeyguardQuickAffordanceSelectionManager()
    }

    @Test
    fun setSelections() =
        runBlocking(IMMEDIATE) {
            var affordanceIdsBySlotId: Map<String, List<String>>? = null
            val job = underTest.selections.onEach { affordanceIdsBySlotId = it }.launchIn(this)
            val slotId1 = "slot1"
            val slotId2 = "slot2"
            val affordanceId1 = "affordance1"
            val affordanceId2 = "affordance2"
            val affordanceId3 = "affordance3"

            underTest.setSelections(
                slotId = slotId1,
                affordanceIds = listOf(affordanceId1),
            )
            assertSelections(
                affordanceIdsBySlotId,
                mapOf(
                    slotId1 to listOf(affordanceId1),
                ),
            )

            underTest.setSelections(
                slotId = slotId2,
                affordanceIds = listOf(affordanceId2),
            )
            assertSelections(
                affordanceIdsBySlotId,
                mapOf(
                    slotId1 to listOf(affordanceId1),
                    slotId2 to listOf(affordanceId2),
                )
            )

            underTest.setSelections(
                slotId = slotId1,
                affordanceIds = listOf(affordanceId1, affordanceId3),
            )
            assertSelections(
                affordanceIdsBySlotId,
                mapOf(
                    slotId1 to listOf(affordanceId1, affordanceId3),
                    slotId2 to listOf(affordanceId2),
                )
            )

            underTest.setSelections(
                slotId = slotId1,
                affordanceIds = listOf(affordanceId3),
            )
            assertSelections(
                affordanceIdsBySlotId,
                mapOf(
                    slotId1 to listOf(affordanceId3),
                    slotId2 to listOf(affordanceId2),
                )
            )

            underTest.setSelections(
                slotId = slotId2,
                affordanceIds = listOf(),
            )
            assertSelections(
                affordanceIdsBySlotId,
                mapOf(
                    slotId1 to listOf(affordanceId3),
                    slotId2 to listOf(),
                )
            )

            job.cancel()
        }

    private suspend fun assertSelections(
        observed: Map<String, List<String>>?,
        expected: Map<String, List<String>>,
    ) {
        assertThat(underTest.getSelections()).isEqualTo(expected)
        assertThat(observed).isEqualTo(expected)
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
