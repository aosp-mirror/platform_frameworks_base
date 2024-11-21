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

package com.android.systemui.shared.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_COMMUNAL_HUB_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickStepContractTest : SysuiTestCase() {
    @Test
    fun isBackGestureDisabled_hubShowing() {
        val sysuiStateFlags = SYSUI_STATE_COMMUNAL_HUB_SHOWING

        // Gestures are disabled while on the hub.
        assertThat(
                QuickStepContract.isBackGestureDisabled(sysuiStateFlags, /* forTrackpad= */ false)
            )
            .isTrue()
    }

    @Test
    fun isBackGestureDisabled_hubAndQSExpanded() {
        val sysuiStateFlags =
            SYSUI_STATE_COMMUNAL_HUB_SHOWING and SYSUI_STATE_QUICK_SETTINGS_EXPANDED

        // Gestures are enabled because the shade shows over the hub.
        assertThat(
                QuickStepContract.isBackGestureDisabled(sysuiStateFlags, /* forTrackpad= */ false)
            )
            .isFalse()
    }

    @Test
    fun isBackGestureDisabled_hubAndBouncerShowing() {
        val sysuiStateFlags = SYSUI_STATE_COMMUNAL_HUB_SHOWING and SYSUI_STATE_BOUNCER_SHOWING

        // Gestures are enabled because the bouncer shows over the hub.
        assertThat(
                QuickStepContract.isBackGestureDisabled(sysuiStateFlags, /* forTrackpad= */ false)
            )
            .isFalse()
    }
}
