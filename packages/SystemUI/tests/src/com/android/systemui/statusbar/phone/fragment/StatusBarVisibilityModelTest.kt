/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone.fragment

import android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.fragment.StatusBarVisibilityModel.Companion.createDefaultModel
import com.android.systemui.statusbar.phone.fragment.StatusBarVisibilityModel.Companion.createModelFromFlags
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class StatusBarVisibilityModelTest : SysuiTestCase() {
    @Test
    fun createDefaultModel_everythingEnabled() {
        val result = createDefaultModel()

        val expected =
            StatusBarVisibilityModel(
                showClock = true,
                showNotificationIcons = true,
                showOngoingActivityChip = true,
                showSystemInfo = true,
            )

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun createModelFromFlags_clockNotDisabled_showClockTrue() {
        val result = createModelFromFlags(disabled1 = 0, disabled2 = 0)

        assertThat(result.showClock).isTrue()
    }

    @Test
    fun createModelFromFlags_clockDisabled_showClockFalse() {
        val result = createModelFromFlags(disabled1 = DISABLE_CLOCK, disabled2 = 0)

        assertThat(result.showClock).isFalse()
    }

    @Test
    fun createModelFromFlags_notificationIconsNotDisabled_showNotificationIconsTrue() {
        val result = createModelFromFlags(disabled1 = 0, disabled2 = 0)

        assertThat(result.showNotificationIcons).isTrue()
    }

    @Test
    fun createModelFromFlags_notificationIconsDisabled_showNotificationIconsFalse() {
        val result = createModelFromFlags(disabled1 = DISABLE_NOTIFICATION_ICONS, disabled2 = 0)

        assertThat(result.showNotificationIcons).isFalse()
    }

    @Test
    fun createModelFromFlags_ongoingCallChipNotDisabled_showOngoingActivityChipTrue() {
        val result = createModelFromFlags(disabled1 = 0, disabled2 = 0)

        assertThat(result.showOngoingActivityChip).isTrue()
    }

    @Test
    fun createModelFromFlags_ongoingCallChipDisabled_showOngoingActivityChipFalse() {
        val result = createModelFromFlags(disabled1 = DISABLE_ONGOING_CALL_CHIP, disabled2 = 0)

        assertThat(result.showOngoingActivityChip).isFalse()
    }

    @Test
    fun createModelFromFlags_systemInfoAndIconsNotDisabled_showSystemInfoTrue() {
        val result = createModelFromFlags(disabled1 = 0, disabled2 = 0)

        assertThat(result.showSystemInfo).isTrue()
    }

    @Test
    fun createModelFromFlags_disable1SystemInfoDisabled_showSystemInfoFalse() {
        val result = createModelFromFlags(disabled1 = DISABLE_SYSTEM_INFO, disabled2 = 0)

        assertThat(result.showSystemInfo).isFalse()
    }

    @Test
    fun createModelFromFlags_disable2SystemIconsDisabled_showSystemInfoFalse() {
        val result = createModelFromFlags(disabled1 = 0, disabled2 = DISABLE2_SYSTEM_ICONS)

        assertThat(result.showSystemInfo).isFalse()
    }
}
