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

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.backup.BackupHelper
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceLocalUserSelectionManagerTest : SysuiTestCase() {

    @Mock private lateinit var userFileManager: UserFileManager

    private lateinit var underTest: KeyguardQuickAffordanceLocalUserSelectionManager

    private lateinit var userTracker: FakeUserTracker
    private lateinit var sharedPrefs: MutableMap<Int, SharedPreferences>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        overrideResource(R.bool.custom_lockscreen_shortcuts_enabled, true)
        sharedPrefs = mutableMapOf()
        whenever(userFileManager.getSharedPreferences(anyString(), anyInt(), anyInt())).thenAnswer {
            val userId = it.arguments[2] as Int
            sharedPrefs.getOrPut(userId) { FakeSharedPreferences() }
        }
        userTracker = FakeUserTracker()
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)

        underTest =
            KeyguardQuickAffordanceLocalUserSelectionManager(
                context = context,
                userFileManager = userFileManager,
                userTracker = userTracker,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
    }

    @After
    fun tearDown() {
        mContext
            .getOrCreateTestableResources()
            .removeOverride(R.bool.custom_lockscreen_shortcuts_enabled)
        mContext
            .getOrCreateTestableResources()
            .removeOverride(R.array.config_keyguardQuickAffordanceDefaults)

        Dispatchers.resetMain()
    }

    @Test
    fun setSelections() = runTest {
        overrideResource(R.array.config_keyguardQuickAffordanceDefaults, arrayOf<String>())
        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }
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
            affordanceIdsBySlotId.last(),
            mapOf(
                slotId1 to listOf(affordanceId1),
            ),
        )

        underTest.setSelections(
            slotId = slotId2,
            affordanceIds = listOf(affordanceId2),
        )
        assertSelections(
            affordanceIdsBySlotId.last(),
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
            affordanceIdsBySlotId.last(),
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
            affordanceIdsBySlotId.last(),
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
            affordanceIdsBySlotId.last(),
            mapOf(
                slotId1 to listOf(affordanceId3),
                slotId2 to listOf(),
            )
        )

        job.cancel()
    }

    @Test
    fun remembersSelectionsByUser() = runTest {
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf<String>(),
        )
        val slot1 = "slot_1"
        val slot2 = "slot_2"
        val affordance1 = "affordance_1"
        val affordance2 = "affordance_2"
        val affordance3 = "affordance_3"

        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }

        val userInfos =
            listOf(
                UserInfo(/* id= */ 0, "zero", /* flags= */ 0),
                UserInfo(/* id= */ 1, "one", /* flags= */ 0),
            )
        userTracker.set(
            userInfos = userInfos,
            selectedUserIndex = 0,
        )
        underTest.setSelections(
            slotId = slot1,
            affordanceIds = listOf(affordance1),
        )
        underTest.setSelections(
            slotId = slot2,
            affordanceIds = listOf(affordance2),
        )

        // Switch to user 1
        userTracker.set(
            userInfos = userInfos,
            selectedUserIndex = 1,
        )
        // We never set selections on user 1, so it should be empty.
        assertSelections(
            observed = affordanceIdsBySlotId.last(),
            expected = emptyMap(),
        )
        // Now, let's set selections on user 1.
        underTest.setSelections(
            slotId = slot1,
            affordanceIds = listOf(affordance2),
        )
        underTest.setSelections(
            slotId = slot2,
            affordanceIds = listOf(affordance3),
        )
        assertSelections(
            observed = affordanceIdsBySlotId.last(),
            expected =
                mapOf(
                    slot1 to listOf(affordance2),
                    slot2 to listOf(affordance3),
                ),
        )

        // Switch back to user 0.
        userTracker.set(
            userInfos = userInfos,
            selectedUserIndex = 0,
        )
        // Assert that we still remember the old selections for user 0.
        assertSelections(
            observed = affordanceIdsBySlotId.last(),
            expected =
                mapOf(
                    slot1 to listOf(affordance1),
                    slot2 to listOf(affordance2),
                ),
        )

        job.cancel()
    }

    @Test
    fun selectionsRespectsDefaults() = runTest {
        val slotId1 = "slot1"
        val slotId2 = "slot2"
        val affordanceId1 = "affordance1"
        val affordanceId2 = "affordance2"
        val affordanceId3 = "affordance3"
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf(
                "$slotId1:${listOf(affordanceId1, affordanceId3).joinToString(",")}",
                "$slotId2:${listOf(affordanceId2).joinToString(",")}",
            ),
        )
        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }

        assertSelections(
            affordanceIdsBySlotId.last(),
            mapOf(
                slotId1 to listOf(affordanceId1, affordanceId3),
                slotId2 to listOf(affordanceId2),
            ),
        )

        job.cancel()
    }

    @Test
    fun selectionsIgnoresDefaultsAfterSelectingAnAffordance() = runTest {
        val slotId1 = "slot1"
        val slotId2 = "slot2"
        val affordanceId1 = "affordance1"
        val affordanceId2 = "affordance2"
        val affordanceId3 = "affordance3"
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf(
                "$slotId1:${listOf(affordanceId1, affordanceId3).joinToString(",")}",
                "$slotId2:${listOf(affordanceId2).joinToString(",")}",
            ),
        )
        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }

        underTest.setSelections(slotId1, listOf(affordanceId2))
        assertSelections(
            affordanceIdsBySlotId.last(),
            mapOf(
                slotId1 to listOf(affordanceId2),
                slotId2 to listOf(affordanceId2),
            ),
        )

        job.cancel()
    }

    @Test
    fun selectionsIgnoresDefaultsAfterClearingAslot() = runTest {
        val slotId1 = "slot1"
        val slotId2 = "slot2"
        val affordanceId1 = "affordance1"
        val affordanceId2 = "affordance2"
        val affordanceId3 = "affordance3"
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf(
                "$slotId1:${listOf(affordanceId1, affordanceId3).joinToString(",")}",
                "$slotId2:${listOf(affordanceId2).joinToString(",")}",
            ),
        )
        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }

        underTest.setSelections(slotId1, listOf())
        assertSelections(
            affordanceIdsBySlotId.last(),
            mapOf(
                slotId1 to listOf(),
                slotId2 to listOf(affordanceId2),
            ),
        )

        job.cancel()
    }

    @Test
    fun respondsToBackupAndRestoreByReloadingTheSelectionsFromDisk() = runTest {
        overrideResource(R.array.config_keyguardQuickAffordanceDefaults, arrayOf<String>())
        val affordanceIdsBySlotId = mutableListOf<Map<String, List<String>>>()
        val job =
            launch(UnconfinedTestDispatcher()) {
                underTest.selections.toList(affordanceIdsBySlotId)
            }
        clearInvocations(userFileManager)

        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(BackupHelper.ACTION_RESTORE_FINISHED),
        )

        verify(userFileManager, atLeastOnce()).getSharedPreferences(anyString(), anyInt(), anyInt())
        job.cancel()
    }

    @Test
    fun getSelections_alwaysReturnsDefaultsIfCustomShortcutsFeatureDisabled() {
        overrideResource(R.bool.custom_lockscreen_shortcuts_enabled, false)
        overrideResource(
            R.array.config_keyguardQuickAffordanceDefaults,
            arrayOf("leftTest:testShortcut1", "rightTest:testShortcut2")
        )

        assertThat(underTest.getSelections())
            .isEqualTo(
                mapOf(
                    "leftTest" to listOf("testShortcut1"),
                    "rightTest" to listOf("testShortcut2"),
                )
            )
    }

    private fun assertSelections(
        observed: Map<String, List<String>>?,
        expected: Map<String, List<String>>,
    ) {
        assertThat(underTest.getSelections()).isEqualTo(expected)
        assertThat(observed).isEqualTo(expected)
    }
}
