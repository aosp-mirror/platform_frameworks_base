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

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceLegacySettingSyncerTest : SysuiTestCase() {

    @Mock private lateinit var sharedPrefs: FakeSharedPreferences

    private lateinit var underTest: KeyguardQuickAffordanceLegacySettingSyncer

    private lateinit var testScope: TestScope
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var selectionManager: KeyguardQuickAffordanceLocalUserSelectionManager
    private lateinit var settings: FakeSettings

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val context: Context = mock()
        sharedPrefs = FakeSharedPreferences()
        whenever(context.getSharedPreferences(anyString(), any())).thenReturn(sharedPrefs)
        val resources: Resources = mock()
        whenever(resources.getStringArray(R.array.config_keyguardQuickAffordanceDefaults))
            .thenReturn(emptyArray())
        whenever(resources.getBoolean(R.bool.custom_lockscreen_shortcuts_enabled)).thenReturn(true)
        whenever(context.resources).thenReturn(resources)

        testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        selectionManager =
            KeyguardQuickAffordanceLocalUserSelectionManager(
                context = context,
                userFileManager =
                    mock {
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
                systemSettings = FakeSettings(),
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
        settings = FakeSettings()
        settings.putInt(Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 0)
        settings.putInt(Settings.Secure.LOCKSCREEN_SHOW_WALLET, 0)
        settings.putInt(Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER, 0)

        underTest =
            KeyguardQuickAffordanceLegacySettingSyncer(
                scope = testScope,
                backgroundDispatcher = testDispatcher,
                secureSettings = settings,
                selectionsManager = selectionManager,
            )
    }

    @Test
    fun settingAsettingSelectsTheAffordance() =
        testScope.runTest {
            val job = underTest.startSyncing()

            settings.putInt(
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                1,
            )

            assertThat(
                    selectionManager
                        .getSelections()
                        .getOrDefault(
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            emptyList()
                        )
                )
                .contains(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)

            job.cancel()
        }

    @Test
    fun clearingAsettingSelectsTheAffordance() =
        testScope.runTest {
            val job = underTest.startSyncing()

            settings.putInt(
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                1,
            )
            settings.putInt(
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
                0,
            )

            assertThat(
                    selectionManager
                        .getSelections()
                        .getOrDefault(
                            KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            emptyList()
                        )
                )
                .doesNotContain(BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS)

            job.cancel()
        }

    @Test
    fun selectingAnAffordanceSetsItsSetting() =
        testScope.runTest {
            val job = underTest.startSyncing()

            selectionManager.setSelections(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                listOf(BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET)
            )

            advanceUntilIdle()
            assertThat(settings.getInt(Settings.Secure.LOCKSCREEN_SHOW_WALLET)).isEqualTo(1)

            job.cancel()
        }

    @Test
    fun unselectingAnAffordanceClearsItsSetting() =
        testScope.runTest {
            val job = underTest.startSyncing()

            selectionManager.setSelections(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                listOf(BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET)
            )
            selectionManager.setSelections(
                KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                emptyList()
            )

            assertThat(settings.getInt(Settings.Secure.LOCKSCREEN_SHOW_WALLET)).isEqualTo(0)

            job.cancel()
        }
}
