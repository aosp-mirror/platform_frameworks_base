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

package com.android.systemui.statusbar.notification.row

import android.app.Flags.FLAG_COMPACT_HEADS_UP_NOTIFICATION
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class HeadsUpStyleProviderImplTest : SysuiTestCase() {

    @Rule @JvmField val setFlagsRule = SetFlagsRule()

    private lateinit var statusBarModeRepositoryStore: FakeStatusBarModeRepository
    private lateinit var headsUpStyleProvider: HeadsUpStyleProviderImpl

    @Before
    fun setUp() {
        statusBarModeRepositoryStore = FakeStatusBarModeRepository()
        statusBarModeRepositoryStore.defaultDisplay.isInFullscreenMode.value = true

        headsUpStyleProvider = HeadsUpStyleProviderImpl(statusBarModeRepositoryStore)
    }

    @Test
    @DisableFlags(FLAG_COMPACT_HEADS_UP_NOTIFICATION)
    fun shouldApplyCompactStyle_returnsFalse_whenCompactFlagDisabled() {
        assertThat(headsUpStyleProvider.shouldApplyCompactStyle()).isFalse()
    }

    @Test
    @EnableFlags(FLAG_COMPACT_HEADS_UP_NOTIFICATION)
    fun shouldApplyCompactStyle_returnsTrue_whenImmersiveModeEnabled() {
        // GIVEN
        statusBarModeRepositoryStore.defaultDisplay.isInFullscreenMode.value = true

        // THEN
        assertThat(headsUpStyleProvider.shouldApplyCompactStyle()).isTrue()
    }

    @Test
    @EnableFlags(FLAG_COMPACT_HEADS_UP_NOTIFICATION)
    fun shouldApplyCompactStyle_returnsFalse_whenImmersiveModeDisabled() {
        // GIVEN
        statusBarModeRepositoryStore.defaultDisplay.isInFullscreenMode.value = false

        // THEN
        assertThat(headsUpStyleProvider.shouldApplyCompactStyle()).isFalse()
    }
}
