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

import android.content.pm.UserInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.shared.customization.data.content.FakeCustomizationProviderClient
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardQuickAffordanceRepository

    private lateinit var config1: FakeKeyguardQuickAffordanceConfig
    private lateinit var config2: FakeKeyguardQuickAffordanceConfig
    private lateinit var userTracker: FakeUserTracker
    private lateinit var client1: FakeCustomizationProviderClient
    private lateinit var client2: FakeCustomizationProviderClient
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        overrideResource(R.bool.custom_lockscreen_shortcuts_enabled, true)
        context.resources.configuration.setLayoutDirection(Locale.US)
        config1 = FakeKeyguardQuickAffordanceConfig(FakeCustomizationProviderClient.AFFORDANCE_1)
        config2 = FakeKeyguardQuickAffordanceConfig(FakeCustomizationProviderClient.AFFORDANCE_2)
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        userTracker = FakeUserTracker()
        val localUserSelectionManager =
            KeyguardQuickAffordanceLocalUserSelectionManager(
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
                userTracker = userTracker,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
        client1 = FakeCustomizationProviderClient()
        client2 = FakeCustomizationProviderClient()
        val remoteUserSelectionManager =
            KeyguardQuickAffordanceRemoteUserSelectionManager(
                scope = testScope.backgroundScope,
                userTracker = userTracker,
                clientFactory =
                    FakeKeyguardQuickAffordanceProviderClientFactory(
                        userTracker,
                    ) { selectedUserId ->
                        when (selectedUserId) {
                            SECONDARY_USER_1 -> client1
                            SECONDARY_USER_2 -> client2
                            else -> error("No set-up client for user $selectedUserId!")
                        }
                    },
                userHandle = UserHandle.SYSTEM,
            )

        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf<String>(),
        )

        underTest =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = testScope.backgroundScope,
                localUserSelectionManager = localUserSelectionManager,
                remoteUserSelectionManager = remoteUserSelectionManager,
                userTracker = userTracker,
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = testScope.backgroundScope,
                        backgroundDispatcher = testDispatcher,
                        secureSettings = FakeSettings(),
                        selectionsManager = localUserSelectionManager,
                    ),
                configs = setOf(config1, config2),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
    }

    @After
    fun tearDown() {
        mContext
            .getOrCreateTestableResources()
            .removeOverride(R.bool.custom_lockscreen_shortcuts_enabled)
    }

    @Test
    fun setSelections() =
        testScope.runTest {
            val configsBySlotId = collectLastValue(underTest.selections)
            val slotId1 = "slot1"
            val slotId2 = "slot2"

            underTest.setSelections(slotId1, listOf(config1.key))
            assertSelections(
                configsBySlotId(),
                mapOf(
                    slotId1 to listOf(config1),
                ),
            )

            underTest.setSelections(slotId2, listOf(config2.key))
            assertSelections(
                configsBySlotId(),
                mapOf(
                    slotId1 to listOf(config1),
                    slotId2 to listOf(config2),
                ),
            )

            underTest.setSelections(slotId1, emptyList())
            underTest.setSelections(slotId2, listOf(config1.key))
            assertSelections(
                configsBySlotId(),
                mapOf(
                    slotId1 to emptyList(),
                    slotId2 to listOf(config1),
                ),
            )
        }

    @Test
    fun getAffordancePickerRepresentations() =
        testScope.runTest {
            assertThat(underTest.getAffordancePickerRepresentations())
                .isEqualTo(
                    listOf(
                        KeyguardQuickAffordancePickerRepresentation(
                            id = config1.key,
                            name = config1.pickerName(),
                            iconResourceId = config1.pickerIconResourceId,
                        ),
                        KeyguardQuickAffordancePickerRepresentation(
                            id = config2.key,
                            name = config2.pickerName(),
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

    @Test
    fun getSlotPickerRepresentations_rightToLeft_slotsReversed() {
        context.resources.configuration.setLayoutDirection(Locale("he", "IL"))
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
                        id = slot3,
                        maxSelectedAffordances = 5,
                    ),
                    KeyguardSlotPickerRepresentation(
                        id = slot2,
                        maxSelectedAffordances = 4,
                    ),
                    KeyguardSlotPickerRepresentation(
                        id = slot1,
                        maxSelectedAffordances = 2,
                    ),
                )
            )
    }

    @Test
    fun selectionsForSecondaryUser() =
        testScope.runTest {
            userTracker.set(
                userInfos =
                    listOf(
                        UserInfo(
                            UserHandle.USER_SYSTEM,
                            "Primary",
                            /* flags= */ 0,
                        ),
                        UserInfo(
                            SECONDARY_USER_1,
                            "Secondary 1",
                            /* flags= */ 0,
                        ),
                        UserInfo(
                            SECONDARY_USER_2,
                            "Secondary 2",
                            /* flags= */ 0,
                        ),
                    ),
                selectedUserIndex = 2,
            )
            client2.insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = FakeCustomizationProviderClient.AFFORDANCE_2,
            )
            val observed = collectLastValue(underTest.selections)

            assertSelections(
                observed = observed(),
                expected =
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                config2,
                            ),
                    )
            )
        }

    private fun assertSelections(
        observed: Map<String, List<KeyguardQuickAffordanceConfig>>?,
        expected: Map<String, List<KeyguardQuickAffordanceConfig>>,
    ) {
        assertThat(observed).isEqualTo(expected)
        assertThat(underTest.getCurrentSelections())
            .isEqualTo(expected.mapValues { (_, configs) -> configs.map { it.key } })
        expected.forEach { (slotId, configs) ->
            assertThat(underTest.getCurrentSelections(slotId)).isEqualTo(configs)
        }
    }

    companion object {
        private const val SECONDARY_USER_1 = UserHandle.MIN_SECONDARY_USER_ID + 1
        private const val SECONDARY_USER_2 = UserHandle.MIN_SECONDARY_USER_ID + 2
    }
}
