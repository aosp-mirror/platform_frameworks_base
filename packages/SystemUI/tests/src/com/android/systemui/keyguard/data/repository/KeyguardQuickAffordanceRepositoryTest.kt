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

package com.android.systemui.keyguard.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceSelectionManager
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardQuickAffordanceRepository

    private lateinit var config1: FakeKeyguardQuickAffordanceConfig
    private lateinit var config2: FakeKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        config1 = FakeKeyguardQuickAffordanceConfig("built_in:1")
        config2 = FakeKeyguardQuickAffordanceConfig("built_in:2")
        val scope = CoroutineScope(IMMEDIATE)
        val selectionManager =
            KeyguardQuickAffordanceSelectionManager(
                context = context,
                userFileManager =
                    mock<UserFileManager>().apply {
                        whenever(
                                getSharedPreferences(
                                    anyString(),
                                    anyInt(),
                                    anyInt(),
                                )
                            )
                            .thenReturn(FakeSharedPreferences())
                    },
                userTracker = FakeUserTracker(),
            )

        underTest =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = scope,
                selectionManager = selectionManager,
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = scope,
                        backgroundDispatcher = IMMEDIATE,
                        secureSettings = FakeSettings(),
                        selectionsManager = selectionManager,
                    ),
                configs = setOf(config1, config2),
            )
    }

    @Test
    fun setSelections() =
        runBlocking(IMMEDIATE) {
            var configsBySlotId: Map<String, List<KeyguardQuickAffordanceConfig>>? = null
            val job = underTest.selections.onEach { configsBySlotId = it }.launchIn(this)
            val slotId1 = "slot1"
            val slotId2 = "slot2"

            underTest.setSelections(slotId1, listOf(config1.key))
            assertSelections(
                configsBySlotId,
                mapOf(
                    slotId1 to listOf(config1),
                ),
            )

            underTest.setSelections(slotId2, listOf(config2.key))
            assertSelections(
                configsBySlotId,
                mapOf(
                    slotId1 to listOf(config1),
                    slotId2 to listOf(config2),
                ),
            )

            underTest.setSelections(slotId1, emptyList())
            underTest.setSelections(slotId2, listOf(config1.key))
            assertSelections(
                configsBySlotId,
                mapOf(
                    slotId1 to emptyList(),
                    slotId2 to listOf(config1),
                ),
            )

            job.cancel()
        }

    @Test
    fun getAffordancePickerRepresentations() {
        assertThat(underTest.getAffordancePickerRepresentations())
            .isEqualTo(
                listOf(
                    KeyguardQuickAffordancePickerRepresentation(
                        id = config1.key,
                        name = config1.pickerName,
                        iconResourceId = config1.pickerIconResourceId,
                    ),
                    KeyguardQuickAffordancePickerRepresentation(
                        id = config2.key,
                        name = config2.pickerName,
                        iconResourceId = config2.pickerIconResourceId,
                    ),
                )
            )
    }

    @Test
    fun getSlotPickerRepresentations() {
        val slot1 = "slot1"
        val slot2 = "slot2"
        val slot3 = "slot3"
        context.orCreateTestableResources.addOverride(
            R.array.config_keyguardQuickAffordanceSlots,
            arrayOf(
                "$slot1:2",
                "$slot2:4",
                "$slot3:5",
            ),
        )

        assertThat(underTest.getSlotPickerRepresentations())
            .isEqualTo(
                listOf(
                    KeyguardSlotPickerRepresentation(
                        id = slot1,
                        maxSelectedAffordances = 2,
                    ),
                    KeyguardSlotPickerRepresentation(
                        id = slot2,
                        maxSelectedAffordances = 4,
                    ),
                    KeyguardSlotPickerRepresentation(
                        id = slot3,
                        maxSelectedAffordances = 5,
                    ),
                )
            )
    }

    private suspend fun assertSelections(
        observed: Map<String, List<KeyguardQuickAffordanceConfig>>?,
        expected: Map<String, List<KeyguardQuickAffordanceConfig>>,
    ) {
        assertThat(observed).isEqualTo(expected)
        assertThat(underTest.getSelections())
            .isEqualTo(expected.mapValues { (_, configs) -> configs.map { it.key } })
        expected.forEach { (slotId, configs) ->
            assertThat(underTest.getSelections(slotId)).isEqualTo(configs)
        }
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
